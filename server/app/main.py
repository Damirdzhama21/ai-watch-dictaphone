"""
watchrec — приём записей с часов → расшифровка → саммари → Telegram.

POST /rec   (Authorization: Bearer <UPLOAD_TOKEN>, multipart: file, device)
GET  /health

Очередь в SQLite, один фоновый поток обрабатывает записи по порядку.
"""
import hmac
import json
import logging
import os
import re
import sqlite3
import subprocess
import tempfile
import threading
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

import httpx
from fastapi import FastAPI, File, Form, Header, HTTPException, UploadFile

from . import web, zadachi

log = logging.getLogger("watchrec")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
# httpx на INFO пишет полный URL запроса, а в URL Telegram — токен бота: в логи его нельзя
logging.getLogger("httpx").setLevel(logging.WARNING)
logging.getLogger("httpcore").setLevel(logging.WARNING)


def env(name: str, default: str = "") -> str:
    return os.environ.get(name, default).strip()


DATA = Path(env("DATA_DIR", "/data"))
AUDIO_DIR = DATA / "audio"
OUT_DIR = DATA / "out"
DB_PATH = DATA / "jobs.db"

UPLOAD_TOKEN = env("UPLOAD_TOKEN")
TG_BOT_TOKEN = env("TG_BOT_TOKEN")
TG_CHAT_ID = env("TG_CHAT_ID")
LANGUAGE = env("LANGUAGE", "ru")
WEB_URL = env("WEB_URL") or (f"https://{env('REC_DOMAIN')}" if env("REC_DOMAIN") else "")
ALMATY = timezone(timedelta(hours=5))

# --- распознавание ---
STT_MODE = env("STT_MODE", "local")                 # local | api
WHISPER_MODEL = env("WHISPER_MODEL", "medium")      # small | medium | large-v3 | large-v3-turbo
WHISPER_COMPUTE = env("WHISPER_COMPUTE", "int8")
WHISPER_THREADS = int(env("WHISPER_THREADS", "0") or 0)
STT_API_URL = env("STT_API_URL", "https://api.groq.com/openai/v1")   # любой OpenAI-совместимый
STT_API_KEY = env("STT_API_KEY")
STT_API_MODEL = env("STT_API_MODEL", "whisper-large-v3")

# --- саммари ---
LLM_PROVIDER = env("LLM_PROVIDER", "anthropic")     # anthropic | openai | none
LLM_MODEL = env("LLM_MODEL", "claude-sonnet-5-5")
ANTHROPIC_API_KEY = env("ANTHROPIC_API_KEY")
LLM_BASE_URL = env("LLM_BASE_URL", "https://api.openai.com/v1")
LLM_API_KEY = env("LLM_API_KEY")
MIN_WORDS_FOR_SUMMARY = int(env("MIN_WORDS_FOR_SUMMARY", "40"))
EMPTY_DROP_SEC = float(env("EMPTY_DROP_SEC", "5"))     # без речи и короче — удаляется молча

SUMMARY_PROMPT = env("SUMMARY_PROMPT") or """Ты помощник руководителя. Ниже расшифровка аудиозаписи \
(совещание, разговор или голосовая заметка) на русском, распознанная автоматически — в ней бывают ошибки.

Сделай выжимку строго в таком виде (пустые разделы пропускай):

Тема: одна строка
Коротко: 2–4 предложения, суть
Решения:
- ...
Цифры и факты: объёмы, суммы, даты, номера техники
Открытые вопросы:
- ...

Задачи отдельным разделом НЕ пиши — их выделяют отдельно. Деньги — в тенге (₸), если не названа другая валюта.
Пиши по-русски, коротко, без воды и без Markdown-разметки (никаких **, #). Не выдумывай того, чего нет в тексте.

Расшифровка:
"""

for d in (AUDIO_DIR, OUT_DIR):
    d.mkdir(parents=True, exist_ok=True)

app = FastAPI(title="watchrec", docs_url=None, redoc_url=None, openapi_url=None)
_wake = threading.Event()
_model = None


# ---------------- БД ----------------
def db():
    con = sqlite3.connect(DB_PATH, timeout=30)
    con.row_factory = sqlite3.Row
    return con


def init_db():
    with db() as con:
        con.execute(
            """CREATE TABLE IF NOT EXISTS jobs(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                filename TEXT NOT NULL,
                device TEXT,
                received_at TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'pending',
                attempts INTEGER NOT NULL DEFAULT 0,
                error TEXT,
                duration_sec REAL,
                summary TEXT
            )"""
        )
        have = {r[1] for r in con.execute("PRAGMA table_info(jobs)")}
        for col, typ in (("title", "TEXT"), ("recorded_at", "TEXT"), ("transcript", "TEXT"),
                         ("words", "INTEGER"), ("search_text", "TEXT")):
            if col not in have:
                con.execute(f"ALTER TABLE jobs ADD COLUMN {col} {typ}")
        con.execute("""CREATE TABLE IF NOT EXISTS tasks(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                job_id INTEGER NOT NULL,
                text TEXT NOT NULL,
                done INTEGER NOT NULL DEFAULT 0,
                done_at TEXT)""")
        con.execute("CREATE INDEX IF NOT EXISTS tasks_job ON tasks(job_id)")
        have_t = {r[1] for r in con.execute("PRAGMA table_info(tasks)")}
        for col in ("due_date", "due_time", "created_at", "reminded_at"):
            if col not in have_t:
                con.execute(f"ALTER TABLE tasks ADD COLUMN {col} TEXT")
        if "tasks_v" not in {r[1] for r in con.execute("PRAGMA table_info(jobs)")}:
            con.execute("ALTER TABLE jobs ADD COLUMN tasks_v INTEGER")
        con.execute("CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY, v TEXT)")
        # записи до веб-архива: подтянуть текст и сводку из out/
        for r in con.execute("SELECT * FROM jobs WHERE status='done' AND transcript IS NULL").fetchall():
            stem = Path(r["filename"]).stem
            tp, sp = OUT_DIR / f"{stem}.txt", OUT_DIR / f"{stem}.summary.txt"
            tr = tp.read_text(encoding="utf-8") if tp.exists() else ""
            sm = r["summary"] or (sp.read_text(encoding="utf-8") if sp.exists() else "")
            save_result(con, r, tr, sm)
        # после рестарта всё, что зависло в работе, снова в очередь
        con.execute("UPDATE jobs SET status='pending' WHERE status='working'")


# ---------------- HTTP ----------------
SAFE_NAME = re.compile(r"[^A-Za-z0-9._-]")


@app.get("/health")
def health():
    with db() as con:
        pending = con.execute("SELECT COUNT(*) FROM jobs WHERE status IN ('pending','working')").fetchone()[0]
    return {"ok": True, "queue": pending, "stt": STT_MODE, "llm": LLM_PROVIDER}


@app.post("/rec")
async def upload(file: UploadFile = File(...), device: str = Form(""), authorization: str = Header("")):
    if not UPLOAD_TOKEN or not hmac.compare_digest(authorization, f"Bearer {UPLOAD_TOKEN}"):
        raise HTTPException(401, "bad token")
    name = SAFE_NAME.sub("_", Path(file.filename or "rec.ogg").name)[:120] or "rec.ogg"
    dest = AUDIO_DIR / name
    if dest.exists():
        # повторная отправка того же файла (часы не получили ответ) — не дублируем
        with db() as con:
            row = con.execute("SELECT id FROM jobs WHERE filename=?", (name,)).fetchone()
        if row:
            return {"ok": True, "id": row["id"], "duplicate": True}
        dest = AUDIO_DIR / f"{int(time.time())}_{name}"
    tmp = dest.with_suffix(dest.suffix + ".part")
    with tmp.open("wb") as f:
        while chunk := await file.read(1 << 20):
            f.write(chunk)
    tmp.rename(dest)
    with db() as con:
        cur = con.execute(
            "INSERT INTO jobs(filename, device, received_at) VALUES(?,?,?)",
            (dest.name, device[:64], datetime.now(ALMATY).replace(tzinfo=None).isoformat(timespec="seconds")),
        )
        job_id = cur.lastrowid
    log.info("received %s (%d KB) job=%s", dest.name, dest.stat().st_size // 1024, job_id)
    _wake.set()
    return {"ok": True, "id": job_id}


# ---------------- обработка ----------------
def audio_duration(path: Path) -> float:
    try:
        out = subprocess.run(
            ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "json", str(path)],
            capture_output=True, text=True, timeout=60,
        )
        return float(json.loads(out.stdout)["format"]["duration"])
    except Exception:
        return 0.0


def fmt_ts(sec: float) -> str:
    sec = int(sec)
    h, m, s = sec // 3600, sec % 3600 // 60, sec % 60
    return f"{h}:{m:02d}:{s:02d}" if h else f"{m:02d}:{s:02d}"


def decode_pcm(path: Path):
    """ffmpeg -> 16 кГц моно float32. Не зависим от версии PyAV внутри faster-whisper."""
    import numpy as np
    out = subprocess.run(
        ["ffmpeg", "-nostdin", "-v", "error", "-i", str(path), "-f", "s16le", "-ac", "1", "-ar", "16000", "-"],
        capture_output=True, check=True, timeout=1800,
    ).stdout
    return np.frombuffer(out, np.int16).astype(np.float32) / 32768.0


def transcribe_local(path: Path) -> list[tuple[float, str]]:
    global _model
    if _model is None:
        from faster_whisper import WhisperModel  # тяжёлый импорт — только когда нужен
        log.info("loading whisper %s (%s)", WHISPER_MODEL, WHISPER_COMPUTE)
        _model = WhisperModel(WHISPER_MODEL, device="cpu", compute_type=WHISPER_COMPUTE,
                              cpu_threads=WHISPER_THREADS)
    segments, _info = _model.transcribe(
        decode_pcm(path), language=LANGUAGE or None, vad_filter=True, beam_size=5,
        condition_on_previous_text=False,
    )
    return [(s.start, s.text.strip()) for s in segments if s.text.strip()]


STT_CHUNK_SEC = int(env("STT_CHUNK_SEC", "300"))   # кусок для API: лимит провайдера 25 МБ + метки времени по кускам


SLOVAR = DATA / "slovar.txt"


def slovar() -> list[str]:
    """Имена и термины Дамира: подсказка распознаванию и выделению задач. Файл правится без пересборки."""
    try:
        return [l.strip() for l in SLOVAR.read_text(encoding="utf-8").splitlines() if l.strip() and not l.startswith("#")][:200]
    except OSError:
        return []


def _echo(text: str, words: list[str]) -> bool:
    """Модель вернула подсказку вместо речи (бывает на очень тихом звуке): «Словарь: …» или половина словаря подряд."""
    t = text.lower()
    if "словарь:" in t:
        return True
    hits = sum(1 for w in words if w.lower() in t)
    return len(words) >= 6 and hits >= len(words) * 0.5


def _stt_api_call(path: Path, name: str, with_prompt: bool = True) -> tuple[list[tuple[float, str]], str]:
    """Один запрос к /audio/transcriptions. whisper-* отдаёт сегменты (verbose_json), gpt-4o-*-transcribe — только текст."""
    verbose = STT_API_MODEL.startswith("whisper")
    words = slovar() if with_prompt else []
    for popytka in range(3):
        with path.open("rb") as f:
            r = httpx.post(
                f"{STT_API_URL.rstrip('/')}/audio/transcriptions",
                headers={"Authorization": f"Bearer {STT_API_KEY}"},
                data={"model": STT_API_MODEL, "language": LANGUAGE, "response_format": "verbose_json" if verbose else "json",
                      **({"prompt": "Словарь: " + ", ".join(words) + "."} if words else {})},
                files={"file": (name, f, "audio/ogg")},
                timeout=600,
            )
        if r.status_code in (429, 500, 502, 503, 504) and popytka < 2:
            time.sleep(10 * (popytka + 1))
            continue
        r.raise_for_status()
        break
    js = r.json()
    if words and _echo(js.get("text", ""), words):
        log.warning("%s: модель вернула словарь вместо речи — повтор без подсказки", name)
        segs, text = _stt_api_call(path, name, with_prompt=False)
        return ([], "") if _echo(text, slovar()) else (segs, text)
    segs = js.get("segments") or []
    return [(float(s.get("start", 0)), s["text"].strip()) for s in segs if s.get("text", "").strip()], js.get("text", "").strip()


# Микрофон часов пишет тихо (средний уровень −45…−50 дБ): срез низа/верха, шумодав, выравнивание громкости.
# На тихой записи без этого модель не слышит речь вовсе. Отключается STT_CLEAN=0.
CLEAN_AF = env("STT_CLEAN_AF", "highpass=f=90,lowpass=f=7500,afftdn=nf=-30,dynaudnorm=f=150:g=15:p=0.9:m=30,loudnorm=I=-18:TP=-2")


def clean_audio(src: Path, td: str) -> Path:
    if env("STT_CLEAN", "1") == "0":
        return src
    dst = Path(td) / (src.stem + ".clean.ogg")
    try:
        subprocess.run(["ffmpeg", "-nostdin", "-v", "error", "-y", "-i", str(src), "-af", CLEAN_AF,
                        "-ar", "16000", "-ac", "1", "-c:a", "libopus", "-b:a", "32k", str(dst)], check=True, timeout=900)
        return dst
    except Exception as e:
        log.warning("очистка звука не удалась (%s) — расшифровываю как есть", str(e)[:200])
        return src


def paragraphs(text: str, start: float, length: float, words_per_par: int = 45) -> list[tuple[float, str]]:
    """Модели без сегментов отдают кусок одним текстом. Режем на абзацы по предложениям,
    метку абзаца оцениваем по доле символов внутри куска (речь идёт ~равномерно) — для перемотки хватает."""
    sents = [x.strip() for x in re.split(r"(?<=[.!?…])\s+", text) if x.strip()]
    total = max(1, len(text))
    out, cur, pos, cur_pos = [], [], 0, 0
    for snt in sents:
        if not cur:
            cur_pos = pos
        cur.append(snt)
        pos += len(snt) + 1
        if sum(len(x.split()) for x in cur) >= words_per_par:
            out.append((start + length * cur_pos / total, " ".join(cur)))
            cur = []
    if cur:
        out.append((start + length * cur_pos / total, " ".join(cur)))
    return out


def transcribe_api(path: Path) -> list[tuple[float, str]]:
    with tempfile.TemporaryDirectory() as ctd:
        return _transcribe_api(clean_audio(path, ctd))


def _transcribe_api(path: Path) -> list[tuple[float, str]]:
    """Режем запись на куски по STT_CHUNK_SEC (ffmpeg, без перекодирования): длинные записи проходят лимит 25 МБ,
    а у моделей без сегментов метка времени = начало куска."""
    dur = audio_duration(path)
    if dur <= STT_CHUNK_SEC + 30:
        segs, text = _stt_api_call(path, path.name)
        return segs or (paragraphs(text, 0.0, dur) if text else [])
    out: list[tuple[float, str]] = []
    with tempfile.TemporaryDirectory() as td:
        subprocess.run(["ffmpeg", "-nostdin", "-v", "error", "-i", str(path), "-f", "segment", "-segment_time", str(STT_CHUNK_SEC),
                        "-c", "copy", "-reset_timestamps", "1", f"{td}/part_%03d.ogg"], check=True, timeout=600)
        for i, part in enumerate(sorted(Path(td).glob("part_*.ogg"))):
            nachalo = i * STT_CHUNK_SEC
            segs, text = _stt_api_call(part, part.name)
            if segs:
                out += [(nachalo + s, t) for s, t in segs]
            elif text:
                out += paragraphs(text, float(nachalo), min(STT_CHUNK_SEC, max(1.0, dur - nachalo)))
    return out


def transcribe(path: Path) -> list[tuple[float, str]]:
    return transcribe_api(path) if STT_MODE == "api" else transcribe_local(path)


def summarize(text: str) -> str:
    if LLM_PROVIDER == "none":
        return ""
    return llm(SUMMARY_PROMPT + text)


def llm(prompt: str, max_tokens: int = 2000) -> str:
    if LLM_PROVIDER == "anthropic":
        r = httpx.post(
            "https://api.anthropic.com/v1/messages",
            headers={"x-api-key": ANTHROPIC_API_KEY, "anthropic-version": "2023-06-01",
                     "content-type": "application/json"},
            json={"model": LLM_MODEL, "max_tokens": max_tokens,
                  "messages": [{"role": "user", "content": prompt}]},
            timeout=300,
        )
        r.raise_for_status()
        return "".join(b.get("text", "") for b in r.json()["content"]).strip()
    r = httpx.post(
        f"{LLM_BASE_URL.rstrip('/')}/chat/completions",
        headers={"Authorization": f"Bearer {LLM_API_KEY}"},
        json={"model": LLM_MODEL, "messages": [{"role": "user", "content": prompt}]},
        timeout=300,
    )
    r.raise_for_status()
    return r.json()["choices"][0]["message"]["content"].strip()


def tg(method: str, **kw):
    if not TG_BOT_TOKEN or not TG_CHAT_ID:
        log.warning("telegram not configured, skip %s", method)
        return
    r = httpx.post(f"https://api.telegram.org/bot{TG_BOT_TOKEN}/{method}",
                   timeout=120, **kw)
    if r.status_code != 200:
        log.error("telegram %s: %s", method, r.text[:300])
        # не raise_for_status(): текст его исключения содержит URL с токеном бота
        raise RuntimeError(f"telegram {method} HTTP {r.status_code}")


def tg_text(text: str):
    # лимит Telegram 4096 символов — режем по строкам
    chunk = ""
    for line in text.splitlines(keepends=True):
        if len(chunk) + len(line) > 3900:
            tg("sendMessage", data={"chat_id": TG_CHAT_ID, "text": chunk})
            chunk = ""
        chunk += line
    if chunk.strip():
        tg("sendMessage", data={"chat_id": TG_CHAT_ID, "text": chunk})


def recorded_dt(filename: str) -> datetime | None:
    """Время начала записи из имени файла часов rec_YYYYMMDD_HHMMSS.ogg (локальное время часов = Алматы)."""
    m = re.search(r"(\d{8})_(\d{6})", filename)
    if not m:
        return None
    try:
        return datetime.strptime(m.group(1) + m.group(2), "%Y%m%d%H%M%S")
    except ValueError:
        return None


def recorded_at(filename: str) -> str:
    dt = recorded_dt(filename)
    return dt.strftime("%d.%m.%Y %H:%M") if dt else ""


def save_result(con, job, transcript: str, summary: str, dur: float | None = None):
    """Результат в БД для веб-архива: тема, задачи, текст, поиск. Повторный прогон задачи не дублирует."""
    title, tasks = web.parse_summary(summary)
    if not title:
        first = re.sub(r"\[\d+:\d\d(?::\d\d)?\]\s*", "", transcript).strip().split("\n")[0]
        title = (first[:80] + "…") if len(first) > 80 else first
    dt = recorded_dt(job["filename"])
    words = len(re.sub(r"\[\d+:\d\d(?::\d\d)?\]", "", transcript).split())
    con.execute("UPDATE jobs SET title=?, recorded_at=?, transcript=?, words=?, summary=?, duration_sec=COALESCE(?, duration_sec) WHERE id=?",
                (title, dt.isoformat(timespec="seconds") if dt else job["received_at"], transcript, words, summary, dur, job["id"]))
    web.refresh_search(con, job["id"])


def job_dt(job) -> datetime:
    return recorded_dt(job["filename"]) or zadachi.now_almaty()


def extract_tasks(job, plain: str) -> list[dict]:
    """Задачи со сроками из любой записи с речью (и короткой заметки тоже). Сбой LLM не валит обработку."""
    if LLM_PROVIDER == "none" or not plain.strip():
        return []
    try:
        return zadachi.extract(llm, plain, job_dt(job), slovar())
    except Exception as e:
        log.warning("job %s: задачи не выделены: %s", job["id"], str(e)[:200])
        return []


def tasks_lines(tasks: list[dict], today) -> str:
    out = []
    for t in tasks:
        due = zadachi.human_due(t["due_date"], t["due_time"], today)
        out.append(f"• {t['text']}" + (f" — {due}" if due else ""))
    return "\n".join(out)


def process(job: sqlite3.Row):
    path = AUDIO_DIR / job["filename"]
    dur = audio_duration(path)
    t0 = time.time()
    segs = transcribe(path)
    stt_sec = time.time() - t0
    stem = Path(job["filename"]).stem
    plain = " ".join(t for _, t in segs)
    timed = "\n".join(f"[{fmt_ts(s)}] {t}" for s, t in segs)
    (OUT_DIR / f"{stem}.txt").write_text(timed, encoding="utf-8")

    words = len(plain.split())
    if words == 0 and dur < EMPTY_DROP_SEC:
        # случайное нажатие: ни речи, ни длины — не захламляем ни ТГ, ни архив
        with db() as con:
            con.execute("DELETE FROM jobs WHERE id=?", (job["id"],))
        for p in (path, OUT_DIR / f"{stem}.txt"):
            p.unlink(missing_ok=True)
        log.info("job %s: пустая запись %.1fs — удалена молча", job["id"], dur)
        return None
    head = f"🎙 {recorded_at(job['filename']) or job['received_at']} · {fmt_ts(dur) if dur else '?'}"
    if words == 0:
        summary = ""
        body = "Речь не распознана (тишина или шум)."
    elif words < MIN_WORDS_FOR_SUMMARY:
        summary = ""
        body = plain               # короткая заметка — просто текст
    else:
        summary = summarize(plain)
        body = summary or plain[:3500]
    if summary:
        (OUT_DIR / f"{stem}.summary.txt").write_text(summary, encoding="utf-8")
    tasks = extract_tasks(job, plain)
    with db() as con:
        save_result(con, job, timed, summary, dur)
        zadachi.save(con, job["id"], tasks, zadachi.now_almaty())
    if tasks:
        body += "\n\n✅ Задачи:\n" + tasks_lines(tasks, zadachi.now_almaty().date())
    web.playable(path)   # AAC-копия для плеера в веб-архиве — заранее, чтобы открывалось сразу

    link = f"\n\n{WEB_URL}/#/r/{job['id']}" if WEB_URL and words else ""
    tg_text(f"{head}\n\n{body}{link}")
    if words >= MIN_WORDS_FOR_SUMMARY:
        with (OUT_DIR / f"{stem}.txt").open("rb") as f:
            tg("sendDocument", data={"chat_id": TG_CHAT_ID, "caption": "Полный текст"},
               files={"document": (f"{stem}.txt", f, "text/plain")})
    log.info("job %s done: %.0fs audio, stt %.0fs, %d words", job["id"], dur, stt_sec, words)
    return dur, summary


def worker():
    while True:
        with db() as con:
            job = con.execute(
                "SELECT * FROM jobs WHERE status='pending' ORDER BY id LIMIT 1"
            ).fetchone()
            if job:
                con.execute("UPDATE jobs SET status='working', attempts=attempts+1 WHERE id=?", (job["id"],))
        if not job:
            backfill_tasks()
            _wake.wait(timeout=30)
            _wake.clear()
            continue
        try:
            res = process(job)
            if res is None:
                continue
            dur, summary = res
            with db() as con:
                con.execute("UPDATE jobs SET status='done', duration_sec=?, summary=?, error=NULL WHERE id=?",
                            (dur, summary, job["id"]))
        except Exception as e:
            log.exception("job %s failed", job["id"])
            final = job["attempts"] + 1 >= 3
            with db() as con:
                con.execute("UPDATE jobs SET status=?, error=? WHERE id=?",
                            ("error" if final else "pending", str(e)[:500], job["id"]))
            if final:
                try:
                    tg_text(f"⚠️ Не смог обработать {job['filename']}: {str(e)[:300]}")
                except Exception:
                    pass
            else:
                time.sleep(60)


def backfill_tasks():
    """Записи, обработанные до выделения задач со сроками: по одной за проход, тихо (без ТГ)."""
    with db() as con:
        r = con.execute("SELECT * FROM jobs WHERE status='done' AND tasks_v IS NULL AND COALESCE(words,0)>0 LIMIT 1").fetchone()
    if not r:
        return
    plain = re.sub(r"\[\d+:\d\d(?::\d\d)?\]\s*", "", r["transcript"] or "")
    tasks = extract_tasks(r, plain)
    with db() as con:
        zadachi.save(con, r["id"], tasks, job_dt(r))
    log.info("backfill job %s: %d задач", r["id"], len(tasks))


@app.on_event("startup")
def _start():
    init_db()
    web.setup(db, AUDIO_DIR, OUT_DIR)
    threading.Thread(target=worker, daemon=True, name="worker").start()
    # напоминания в ТГ; когда их берёт на себя Android-приложение — TG_REMINDERS=0, чтобы не дублировать
    if TG_BOT_TOKEN and TG_CHAT_ID and env("TG_REMINDERS", "1") != "0":
        threading.Thread(target=zadachi.loop, args=(db, tg_text, WEB_URL), daemon=True, name="reminders").start()
    log.info("watchrec up: stt=%s model=%s llm=%s", STT_MODE,
             WHISPER_MODEL if STT_MODE == "local" else STT_API_MODEL, LLM_PROVIDER)


app.include_router(web.router)
