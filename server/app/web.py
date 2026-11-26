"""Веб-архив диктофона: вход по паролю, список записей, поиск, карточка, аудио, задачи.

Хранилище — та же SQLite (jobs + tasks). Сессия — подписанная cookie (HMAC), без серверного состояния.
"""
import hashlib
import hmac
import os
import re
import sqlite3
import subprocess
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

from fastapi import APIRouter, HTTPException, Request, Response
from fastapi.responses import FileResponse
from pydantic import BaseModel

WEB_PASSWORD = os.environ.get("WEB_PASSWORD", "").strip()
WEB_SECRET = (os.environ.get("WEB_SECRET", "").strip() or WEB_PASSWORD).encode()
COOKIE = "wr_s"
SESSION_DAYS = 180
ALMATY = timezone(timedelta(hours=5))
STATIC = Path(__file__).parent / "static"

router = APIRouter()
_db = None           # подставляет main.py: функция-коннектор
_paths = {}          # AUDIO_DIR / OUT_DIR из main.py
_fails: dict[str, list[float]] = {}


def setup(db, audio_dir: Path, out_dir: Path):
    global _db
    _db = db
    _paths.update(audio=audio_dir, out=out_dir)


# ---------------- сессия ----------------
def _sign(exp: int) -> str:
    sig = hmac.new(WEB_SECRET, f"wr|{exp}".encode(), hashlib.sha256).hexdigest()
    return f"{exp}.{sig}"


def _ok_cookie(v: str | None) -> bool:
    if not v or not WEB_PASSWORD or "." not in v:
        return False
    exp, _ = v.split(".", 1)
    if not exp.isdigit() or int(exp) < time.time():
        return False
    return hmac.compare_digest(v, _sign(int(exp)))


def need_auth(req: Request):
    # браузер — cookie; Android-приложение — тот же подписанный токен в заголовке
    h = req.headers.get("authorization", "")
    tok = h[7:].strip() if h.lower().startswith("bearer ") else None
    if not (_ok_cookie(req.cookies.get(COOKIE)) or _ok_cookie(tok)):
        raise HTTPException(401, "нужен вход")


def _ip(req: Request) -> str:
    return (req.headers.get("x-forwarded-for") or (req.client.host if req.client else "")).split(",")[0].strip()


class Login(BaseModel):
    password: str


@router.post("/api/login")
def login(body: Login, req: Request, resp: Response):
    ip = _ip(req)
    now = time.time()
    tries = [t for t in _fails.get(ip, []) if now - t < 600]
    if len(tries) >= 8:
        raise HTTPException(429, "слишком много попыток, подождите 10 минут")
    if not WEB_PASSWORD or not hmac.compare_digest(body.password.strip().encode(), WEB_PASSWORD.encode()):
        tries.append(now)
        _fails[ip] = tries
        raise HTTPException(401, "неверный пароль")
    _fails.pop(ip, None)
    exp = int(now) + SESSION_DAYS * 86400
    tok = _sign(exp)
    resp.set_cookie(COOKIE, tok, max_age=SESSION_DAYS * 86400, httponly=True, secure=True, samesite="lax", path="/")
    return {"ok": True, "token": tok, "expires": exp}


@router.post("/api/logout")
def logout(resp: Response):
    resp.delete_cookie(COOKIE, path="/")
    return {"ok": True}


@router.get("/api/me")
def me(req: Request):
    need_auth(req)
    return {"ok": True}


# ---------------- записи ----------------
def _row(r: sqlite3.Row, full=False) -> dict:
    d = {
        "id": r["id"],
        "recorded": r["recorded_at"] or "",
        "duration": r["duration_sec"] or 0,
        "title": r["title"] or "",
        "preview": _preview(r),
        "status": r["status"],
        "words": r["words"] or 0,
        "tasks_open": r["tasks_open"] if "tasks_open" in r.keys() else 0,
    }
    if full:
        d.update(summary=r["summary"] or "", transcript=r["transcript"] or "", error=r["error"] or "",
                 has_audio=(_paths["audio"] / r["filename"]).exists())
    return d


def _preview(r: sqlite3.Row) -> str:
    s = r["summary"] or ""
    m = re.search(r"^Коротко:\s*(.+)$", s, re.M)
    if m:
        return m.group(1).strip()[:240]
    t = re.sub(r"\[\d+:\d\d(?::\d\d)?\]\s*", "", r["transcript"] or "")
    return " ".join(t.split())[:240]


LIST_SQL = """SELECT j.*, (SELECT COUNT(*) FROM tasks t WHERE t.job_id=j.id AND t.done=0) AS tasks_open
              FROM jobs j"""


@router.get("/api/recs")
def recs(req: Request, q: str = "", limit: int = 50, offset: int = 0):
    need_auth(req)
    limit = max(1, min(limit, 200))
    args: list = []
    where = ""
    words = [w for w in q.lower().split() if w][:6]
    if words:
        # SQLite lower() не знает кириллицу — ищем по заранее приведённой search_text
        where = " WHERE " + " AND ".join("j.search_text LIKE ?" for _ in words)
        args = [f"%{w}%" for w in words]
    with _db() as con:
        rows = con.execute(f"{LIST_SQL}{where} ORDER BY COALESCE(j.recorded_at, j.received_at) DESC, j.id DESC LIMIT ? OFFSET ?",
                           (*args, limit + 1, offset)).fetchall()
    return {"items": [_row(r) for r in rows[:limit]], "more": len(rows) > limit}


def _job(con, rid: int) -> sqlite3.Row:
    r = con.execute(f"{LIST_SQL} WHERE j.id=?", (rid,)).fetchone()
    if not r:
        raise HTTPException(404, "нет такой записи")
    return r


@router.get("/api/recs/{rid}")
def rec(rid: int, req: Request):
    need_auth(req)
    with _db() as con:
        r = _job(con, rid)
        tasks = con.execute("SELECT id, text, done, due_date, due_time FROM tasks WHERE job_id=? ORDER BY id", (rid,)).fetchall()
    d = _row(r, full=True)
    d["tasks"] = [dict(t) for t in tasks]
    return d


@router.get("/api/recs/{rid}/audio")
def audio(rid: int, req: Request):
    need_auth(req)
    with _db() as con:
        r = _job(con, rid)
    p = _paths["audio"] / r["filename"]
    if not p.exists():
        raise HTTPException(404, "аудио удалено")
    m4a = playable(p)
    if m4a:
        return FileResponse(m4a, media_type="audio/mp4")
    return FileResponse(p, media_type="audio/ogg")


def playable(src: Path) -> Path | None:
    """AAC-копия для плеера: Safari на iPhone не везде играет Ogg/Opus. Делается один раз и кэшируется в out/."""
    dst = _paths["out"] / f"{src.stem}.m4a"
    if dst.exists() and dst.stat().st_mtime >= src.stat().st_mtime:
        return dst
    tmp = dst.with_suffix(".part.m4a")
    try:
        subprocess.run(["ffmpeg", "-nostdin", "-v", "error", "-y", "-i", str(src), "-ac", "1", "-c:a", "aac", "-b:a", "48k",
                        "-movflags", "+faststart", str(tmp)], check=True, timeout=900)
        tmp.rename(dst)
        return dst
    except Exception:
        tmp.unlink(missing_ok=True)
        return None


class RecPatch(BaseModel):
    title: str


@router.patch("/api/recs/{rid}")
def rec_patch(rid: int, body: RecPatch, req: Request):
    need_auth(req)
    with _db() as con:
        _job(con, rid)
        con.execute("UPDATE jobs SET title=? WHERE id=?", (body.title.strip()[:200], rid))
        refresh_search(con, rid)
    return {"ok": True}


@router.delete("/api/recs/{rid}")
def rec_delete(rid: int, req: Request):
    need_auth(req)
    with _db() as con:
        r = _job(con, rid)
        if r["status"] in ("pending", "working"):
            raise HTTPException(409, "запись ещё обрабатывается")
        con.execute("DELETE FROM tasks WHERE job_id=?", (rid,))
        con.execute("DELETE FROM jobs WHERE id=?", (rid,))
    stem = Path(r["filename"]).stem
    for p in (_paths["audio"] / r["filename"], _paths["out"] / f"{stem}.txt", _paths["out"] / f"{stem}.summary.txt",
              _paths["out"] / f"{stem}.m4a"):
        p.unlink(missing_ok=True)
    return {"ok": True}


# ---------------- задачи ----------------
@router.get("/api/tasks")
def tasks(req: Request, done: int = 0):
    need_auth(req)
    with _db() as con:
        rows = con.execute(
            f"""SELECT t.id, t.text, t.done, t.done_at, t.due_date, t.due_time, j.id AS rec_id, j.title,
                      COALESCE(j.recorded_at, j.received_at) AS recorded
               FROM tasks t JOIN jobs j ON j.id=t.job_id WHERE t.done=?
               ORDER BY {"t.done_at DESC" if done else "t.due_date IS NULL, t.due_date, t.due_time IS NULL, t.due_time, recorded DESC"}, t.id
               LIMIT 300""", (1 if done else 0,)).fetchall()
    return {"items": [dict(r) for r in rows]}


class TaskPatch(BaseModel):
    done: bool


@router.patch("/api/tasks/{tid}")
def task_patch(tid: int, body: TaskPatch, req: Request):
    need_auth(req)
    with _db() as con:
        n = con.execute("UPDATE tasks SET done=?, done_at=? WHERE id=?",
                        (1 if body.done else 0, datetime.now(ALMATY).isoformat(timespec="seconds") if body.done else None, tid)).rowcount
    if not n:
        raise HTTPException(404, "нет такой задачи")
    return {"ok": True}


# ---------------- общее для main.py ----------------
def refresh_search(con, rid: int):
    r = con.execute("SELECT title, summary, transcript FROM jobs WHERE id=?", (rid,)).fetchone()
    if r:
        txt = " ".join(x or "" for x in (r["title"], r["summary"], r["transcript"])).lower().replace("ё", "е")
        con.execute("UPDATE jobs SET search_text=? WHERE id=?", (txt, rid))


def parse_summary(summary: str) -> tuple[str, list[str]]:
    """Из сводки: тема (строка «Тема:») и пункты раздела «Задачи:»."""
    title = ""
    m = re.search(r"^Тема:\s*(.+)$", summary or "", re.M)
    if m:
        title = m.group(1).strip()[:200]
    tasks, inside = [], False
    for line in (summary or "").splitlines():
        s = line.strip()
        if re.match(r"^Задачи:\s*$", s):
            inside = True
            continue
        if inside:
            if s.startswith(("-", "•", "—")):
                t = s.lstrip("-•— ").strip()
                t = re.sub(r"^(не указано|не назван[оa]?)\b[^—–-]*[—–-]\s*", "", t, flags=re.I)
                if t:
                    tasks.append(t[:500])
            elif s and re.match(r"^[А-ЯA-Z][^:]{1,40}:", s):
                break
    return title, tasks


# ---------------- статика (PWA) ----------------
@router.get("/")
def index():
    return FileResponse(STATIC / "index.html", headers={"Cache-Control": "no-cache"})


@router.get("/sw.js")
def sw():
    return FileResponse(STATIC / "sw.js", media_type="text/javascript", headers={"Cache-Control": "no-cache"})


@router.get("/.well-known/assetlinks.json")
def assetlinks():
    # подтверждение домена для Android-приложения: ссылки на сервер из Telegram открываются сразу в нём
    # отпечаток своего ключа подписи: ANDROID_CERT_SHA256=AA:BB:… (apksigner verify --print-certs)
    fp = os.environ.get("ANDROID_CERT_SHA256", "").strip()
    if not fp:
        raise HTTPException(404)
    return [{"relation": ["delegate_permission/common.handle_all_urls"],
             "target": {"namespace": "android_app", "package_name": os.environ.get("ANDROID_PACKAGE", "kz.damird.diktofon"),
                        "sha256_cert_fingerprints": [fp]}}]


@router.get("/manifest.webmanifest")
def manifest():
    return FileResponse(STATIC / "manifest.webmanifest", media_type="application/manifest+json")


@router.get("/icon-{size}.png")
def icon(size: int):
    p = STATIC / f"icon-{size}.png"
    if not p.exists():
        raise HTTPException(404)
    return FileResponse(p, media_type="image/png", headers={"Cache-Control": "max-age=604800"})
