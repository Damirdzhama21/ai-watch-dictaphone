"""Задачи из записей: извлечение со сроками (LLM → JSON, проверка в коде) и напоминания в Telegram.

Время — Алматы (UTC+5): так идут часы, так думает Дамир. Контейнер живёт в UTC, поэтому везде явно ALMATY.
"""
import json
import logging
import re
import time
from datetime import date, datetime, timedelta, timezone

log = logging.getLogger("watchrec")
ALMATY = timezone(timedelta(hours=5))
DIGEST_HOUR = 9                    # утренняя сводка задач на день
REMIND_LATE_MAX = timedelta(hours=6)   # проспали напоминание дольше — уже не шлём (рестарт, простой)
MAX_TASKS = 20
DNI = ["понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье"]
MES = ["января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря"]

PROMPT = """Ты выделяешь задачи из расшифровки голосовой записи руководителя (автораспознавание, бывают ошибки).
Запись сделана: {when} ({weekday}), время Алматы.

Верни ТОЛЬКО JSON-массив без пояснений. Каждый элемент:
{{"text": "что сделать — коротко, в повелительном виде, с кем/чем", "date": "YYYY-MM-DD" или null, "time": "HH:MM" или null}}

Правила:
- Задача = то, что нужно сделать или не забыть (позвонить, отправить, купить, проверить, встретиться, напомнить).
  Просто факты, рассуждения и уже сделанное — не задачи.
- Срок вычисляй от даты записи: «завтра», «в пятницу», «через неделю», «26-го». Если назван и день недели, и число —
  верь числу. Запись между 00:00 и 05:00 — человек ещё не ложился: «завтра» = дата записи, «сегодня» = предыдущий день.
- time — только если прозвучало время («в 10», «к трём часам» → 15:00 если речь о дне). Без даты, но со временем —
  date = дата записи, если это время ещё впереди, иначе следующий день.
- Сроков нет — null. Не выдумывай.
- Задач нет — верни [].

Расшифровка:
"""


def extract(llm, text: str, rec: datetime, slovar: list[str] | None = None) -> list[dict]:
    """llm(prompt, max_tokens) -> str. Ответ модели проверяется здесь, а не на слово."""
    hint = ""
    if slovar:
        hint = ("Имена и термины, которые часто звучат (распознавание их коверкает — если в тексте явно искажённая "
                "форма одного из них, пиши правильную; не подставляй, если не похоже): " + ", ".join(slovar) + "\n\n")
    raw = llm(PROMPT.format(when=rec.strftime("%Y-%m-%d %H:%M"), weekday=DNI[rec.weekday()]) .replace("Расшифровка:\n", hint + "Расшифровка:\n") + text, 1500)
    m = re.search(r"\[.*\]", raw, re.S)
    if not m:
        log.warning("tasks: нет JSON в ответе")
        return []
    try:
        items = json.loads(m.group(0))
    except ValueError:
        log.warning("tasks: битый JSON")
        return []
    out, seen = [], set()
    for it in items if isinstance(items, list) else []:
        if not isinstance(it, dict):
            continue
        t = " ".join(str(it.get("text") or "").split())[:300]
        if len(t) < 3 or t.lower() in seen:
            continue
        seen.add(t.lower())
        d = _date(it.get("date"), rec.date())
        tm = _time(it.get("time")) if d else None
        out.append({"text": t, "due_date": d, "due_time": tm})
        if len(out) >= MAX_TASKS:
            break
    return out


def _date(v, base: date) -> str | None:
    try:
        d = date.fromisoformat(str(v)[:10])
    except (TypeError, ValueError):
        return None
    # срок раньше записи на день допускаем (полночь), дальше года — скорее ошибка распознавания
    if not (base - timedelta(days=1) <= d <= base + timedelta(days=366)):
        return None
    return d.isoformat()


def _time(v) -> str | None:
    m = re.fullmatch(r"(\d{1,2}):(\d{2})", str(v or "").strip())
    if not m or int(m.group(1)) > 23 or int(m.group(2)) > 59:
        return None
    return f"{int(m.group(1)):02d}:{m.group(2)}"


def save(con, job_id: int, tasks: list[dict], created: datetime):
    """Перезаписать открытые задачи записи (повторная обработка не плодит дублей; закрытые не трогаем)."""
    con.execute("DELETE FROM tasks WHERE job_id=? AND done=0", (job_id,))
    for t in tasks:
        con.execute("INSERT INTO tasks(job_id, text, due_date, due_time, created_at) VALUES(?,?,?,?,?)",
                    (job_id, t["text"], t["due_date"], t["due_time"], created.isoformat(timespec="seconds")))
    con.execute("UPDATE jobs SET tasks_v=1 WHERE id=?", (job_id,))


def human_due(d: str | None, tm: str | None, today: date) -> str:
    if not d:
        return ""
    dd = date.fromisoformat(d)
    diff = (dd - today).days
    s = {0: "сегодня", 1: "завтра", -1: "вчера"}.get(diff) or f"{dd.day} {MES[dd.month - 1]}"
    if 1 < diff < 7:
        s = f"{DNI[dd.weekday()]}, {s}"
    return f"{s} в {tm}" if tm else s


def now_almaty() -> datetime:
    return datetime.now(ALMATY).replace(tzinfo=None)


# ---------------- напоминания ----------------
def tick(con, send, web_url: str):
    """Раз в минуту: напоминания ко времени + утренняя сводка. send(text) шлёт в Telegram."""
    now = now_almaty()
    today = now.date()
    link = f"\n{web_url}/#/tasks" if web_url else ""

    # 1) ко времени: due_date+due_time наступило, задача открыта, не напоминали, создана раньше срока
    rows = con.execute("""SELECT id, text, due_date, due_time, created_at FROM tasks
                          WHERE done=0 AND reminded_at IS NULL AND due_date IS NOT NULL AND due_time IS NOT NULL""").fetchall()
    for r in rows:
        due = datetime.fromisoformat(f"{r['due_date']}T{r['due_time']}")
        created = datetime.fromisoformat(r["created_at"]) if r["created_at"] else due - timedelta(days=1)
        if due > now:
            continue
        mark = now.isoformat(timespec="seconds")
        if due <= created or now - due > REMIND_LATE_MAX:
            con.execute("UPDATE tasks SET reminded_at=? WHERE id=?", ("skip:" + mark, r["id"]))
            continue
        send(f"⏰ {r['due_time']} — {r['text']}{link}")
        con.execute("UPDATE tasks SET reminded_at=? WHERE id=?", (mark, r["id"]))
        con.commit()   # отправлено — фиксируем сразу, чтобы сбой следующей не вызвал повтор этой

    # 2) утренняя сводка: раз в день после DIGEST_HOUR
    if now.hour < DIGEST_HOUR:
        return
    last = con.execute("SELECT v FROM meta WHERE k='digest'").fetchone()
    if last and last["v"] == today.isoformat():
        return
    con.execute("INSERT INTO meta(k, v) VALUES('digest', ?) ON CONFLICT(k) DO UPDATE SET v=excluded.v", (today.isoformat(),))
    if now.hour >= DIGEST_HOUR + 3:
        return   # сервер лежал утром — запоздалая «утренняя» сводка в обед только мешает
    segodnya = con.execute("""SELECT text, due_time FROM tasks WHERE done=0 AND due_date=?
                              ORDER BY due_time IS NULL, due_time, id""", (today.isoformat(),)).fetchall()
    prosrok = con.execute("""SELECT text, due_date FROM tasks WHERE done=0 AND due_date < ? AND due_date >= ?
                             ORDER BY due_date, id""", (today.isoformat(), (today - timedelta(days=7)).isoformat())).fetchall()
    if not segodnya and not prosrok:
        return
    lines = []
    if segodnya:
        lines.append("📋 Задачи на сегодня:")
        lines += [f"• {r['due_time'] + ' — ' if r['due_time'] else ''}{r['text']}" for r in segodnya]
    if prosrok:
        lines.append("\n⚠️ Просрочено:")
        lines += [f"• {r['text']} (срок {human_due(r['due_date'], None, today)})" for r in prosrok[:10]]
    send("\n".join(lines) + ("\n" + link.strip() if link else ""))


def loop(db, send, web_url: str):
    while True:
        try:
            with db() as con:
                tick(con, send, web_url)
        except Exception:
            log.exception("reminders tick failed")
        time.sleep(60 - time.time() % 60 + 1)
