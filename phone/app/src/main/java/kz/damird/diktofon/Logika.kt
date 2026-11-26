package kz.damird.diktofon

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** Время — Алматы (UTC+5): так пишут часы и так хранит сервер, независимо от пояса телефона. */
object Vremya {
    val ALMATY: ZoneId = ZoneOffset.ofHours(5)
    private val MES = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")
    private val DN = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")
    private val DN_FULL = listOf("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье")

    fun now(): LocalDateTime = LocalDateTime.now(ALMATY)
    fun today(): LocalDate = now().toLocalDate()

    fun parse(s: String?): LocalDateTime? = runCatching { LocalDateTime.parse(s!!.take(19)) }.getOrNull()
    fun parseDate(s: String?): LocalDate? = runCatching { LocalDate.parse(s!!.take(10)) }.getOrNull()
    fun parseTime(s: String?): LocalTime? = runCatching { LocalTime.parse(s!!) }.getOrNull()

    /** «Сегодня», «Вчера», «23 ноября, вс». */
    fun dayLabel(d: LocalDate, today: LocalDate = today()): String {
        val diff = ChronoUnit.DAYS.between(d, today)
        return when (diff) {
            0L -> "Сегодня"
            1L -> "Вчера"
            else -> "${d.dayOfMonth} ${MES[d.monthValue - 1]}" + (if (d.year != today.year) " ${d.year}" else "") + ", ${DN[d.dayOfWeek.value - 1]}"
        }
    }

    fun hm(t: LocalDateTime) = "%02d:%02d".format(t.hour, t.minute)

    fun dur(sec: Double): String {
        val s = sec.toLong().coerceAtLeast(0)
        val h = s / 3600; val m = s % 3600 / 60; val ss = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss)
    }

    fun durWords(sec: Double): String {
        val m = Math.round(sec / 60.0)
        return if (m < 1) "меньше минуты" else "$m мин"
    }

    /** Срок задачи по-человечески + признак «сегодня»/«просрочено». */
    data class Srok(val text: String, val today: Boolean, val late: Boolean)

    fun srok(dueDate: String?, dueTime: String?, today: LocalDate = today()): Srok? {
        val d = parseDate(dueDate) ?: return null
        val diff = ChronoUnit.DAYS.between(today, d)
        var lab = when (diff) {
            0L -> "сегодня"
            1L -> "завтра"
            -1L -> "вчера"
            else -> "${d.dayOfMonth} ${MES[d.monthValue - 1]}"
        }
        if (diff in 2..6) lab = "${DN_FULL[d.dayOfWeek.value - 1]}, $lab"
        if (dueTime != null && parseTime(dueTime) != null) lab += " в $dueTime"
        return Srok(if (diff < 0) "просрочено · $lab" else lab, diff == 0L, diff < 0)
    }

    fun plural(n: Int, one: String, few: String, many: String): String {
        val k = n % 10; val h = n % 100
        val w = if (k == 1 && h != 11) one else if (k in 2..4 && (h < 12 || h > 14)) few else many
        return "$n $w"
    }
}

/** Разбор сводки сервера на разделы «Коротко», «Решения», … */
object Svodka {
    val RAZDELY = listOf("Коротко", "Решения", "Цифры и факты", "Открытые вопросы")
    private val head = Regex("""^([А-ЯЁA-Z][^:]{1,30}):\s*(.*)$""")

    fun parse(s: String): Map<String, List<String>> {
        val out = linkedMapOf<String, MutableList<String>>()
        var cur: String? = null
        for (raw in s.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val m = head.find(line)
            if (m != null && (m.groupValues[1] in RAZDELY || m.groupValues[1] == "Тема" || m.groupValues[1] == "Задачи")) {
                cur = m.groupValues[1]
                out[cur] = mutableListOf<String>().also { if (m.groupValues[2].isNotBlank()) it += m.groupValues[2] }
                continue
            }
            cur?.let { out.getValue(it) += line.trimStart('-', '•', '—', ' ') }
        }
        return out
    }
}

/** Расшифровка «[мм:сс] текст» → абзацы с секундой начала. */
object Rasshifrovka {
    data class Abzac(val sec: Int, val text: String)
    private val re = Regex("""^\[(?:(\d+):)?(\d+):(\d\d)]\s*(.*)$""")

    fun parse(t: String): List<Abzac> = t.lines().mapNotNull { l ->
        re.find(l.trim())?.let { m ->
            val (h, mm, ss, tx) = m.destructured
            Abzac((h.toIntOrNull() ?: 0) * 3600 + mm.toInt() * 60 + ss.toInt(), tx)
        }
    }
}

/** Когда напоминать: ко времени задачи; без времени — только в утренней сводке. */
object Plan {
    const val DIGEST_HOUR = 9

    /** Момент напоминания (эпоха, мс) или null, если ко времени не напоминаем. */
    fun remindAt(t: Task, now: LocalDateTime = Vremya.now()): Long? {
        if (t.done != 0) return null
        val d = Vremya.parseDate(t.dueDate) ?: return null
        val tm = Vremya.parseTime(t.dueTime) ?: return null
        val at = LocalDateTime.of(d, tm)
        if (at.isBefore(now)) return null
        return at.atZone(Vremya.ALMATY).toInstant().toEpochMilli()
    }

    /** Ближайшие 09:00 по Алматы (сегодня, если ещё не наступило). */
    fun nextDigest(now: LocalDateTime = Vremya.now()): Long {
        var at = now.toLocalDate().atTime(DIGEST_HOUR, 0)
        if (!at.isAfter(now)) at = at.plusDays(1)
        return at.atZone(Vremya.ALMATY).toInstant().toEpochMilli()
    }

    /** Текст утренней сводки: на сегодня (по времени, потом без) + просрочка за неделю. */
    fun digest(tasks: List<Task>, today: LocalDate = Vremya.today()): Pair<List<String>, List<String>> {
        val open = tasks.filter { it.done == 0 }
        val seg = open.filter { Vremya.parseDate(it.dueDate) == today }
            .sortedWith(compareBy<Task>({ it.dueTime == null }, { it.dueTime ?: "" }))
            .map { (it.dueTime?.let { t -> "$t — " } ?: "") + it.text }
        val late = open.filter {
            val d = Vremya.parseDate(it.dueDate)
            d != null && d.isBefore(today) && !d.isBefore(today.minusDays(7))
        }.sortedBy { it.dueDate }.map { "${it.text} (срок ${Vremya.srok(it.dueDate, null, today)?.text?.removePrefix("просрочено · ")})" }
        return seg to late
    }
}
