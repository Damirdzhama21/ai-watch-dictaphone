package kz.damird.diktofon

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import kz.damird.diktofon.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.LocalDateTime

class LogikaTest {
    private val today = LocalDate.of(2026, 11, 26)

    @Test fun srok() {
        assertEquals("сегодня", Vremya.srok("2026-11-26", null, today)!!.text)
        assertTrue(Vremya.srok("2026-11-26", null, today)!!.today)
        assertEquals("завтра в 10:00", Vremya.srok("2026-11-27", "10:00", today)!!.text)
        assertEquals("суббота, 28 ноября", Vremya.srok("2026-11-28", null, today)!!.text)
        assertEquals("просрочено · 24 ноября", Vremya.srok("2026-11-24", null, today)!!.text)
        assertTrue(Vremya.srok("2026-11-24", null, today)!!.late)
        assertNull(Vremya.srok(null, "10:00", today))
        assertNull(Vremya.srok("мусор", null, today))
    }

    @Test fun dayLabel() {
        assertEquals("Сегодня", Vremya.dayLabel(today, today))
        assertEquals("Вчера", Vremya.dayLabel(today.minusDays(1), today))
        assertEquals("23 ноября, пн", Vremya.dayLabel(LocalDate.of(2026, 11, 23), today))
        assertEquals("31 декабря 2025, ср", Vremya.dayLabel(LocalDate.of(2025, 12, 31), today))
    }

    @Test fun plural() {
        assertEquals("1 задача", Vremya.plural(1, "задача", "задачи", "задач"))
        assertEquals("3 задачи", Vremya.plural(3, "задача", "задачи", "задач"))
        assertEquals("11 задач", Vremya.plural(11, "задача", "задачи", "задач"))
        assertEquals("22 задачи", Vremya.plural(22, "задача", "задачи", "задач"))
    }

    @Test fun rasshifrovka() {
        val a = Rasshifrovka.parse("[00:00] Так, записываю.\n[00:40] И не забыть.\n[1:02:05] Длинная.\nмусор")
        assertEquals(listOf(0, 40, 3725), a.map { it.sec })
        assertEquals("И не забыть.", a[1].text)
    }

    @Test fun svodka() {
        val s = Svodka.parse("Тема: Встреча\n\nКоротко: Суть.\nЕщё строка.\n\nРешения:\n- Первое\n- Второе\n\nЦифры и факты:\n- 2 машины")
        assertEquals(listOf("Суть.", "Ещё строка."), s["Коротко"])
        assertEquals(listOf("Первое", "Второе"), s["Решения"])
        assertEquals(listOf("2 машины"), s["Цифры и факты"])
    }

    @Test fun remindAt() {
        val now = LocalDateTime.of(2026, 11, 26, 9, 30)
        val t = Task(1, "Позвонить", dueDate = "2026-11-26", dueTime = "10:00")
        // 10:00 Алматы = 05:00 UTC
        assertEquals(java.time.Instant.parse("2026-11-26T05:00:00Z").toEpochMilli(), Plan.remindAt(t, now))
        assertNull(Plan.remindAt(t.copy(dueTime = "09:00"), now))          // уже прошло
        assertNull(Plan.remindAt(t.copy(dueTime = null), now))             // без времени — только в сводке
        assertNull(Plan.remindAt(t.copy(done = 1), now))
        assertEquals(java.time.Instant.parse("2026-11-27T04:00:00Z").toEpochMilli(), Plan.nextDigest(now))
        assertEquals(java.time.Instant.parse("2026-11-26T04:00:00Z").toEpochMilli(), Plan.nextDigest(now.withHour(7)))
    }

    @Test fun digest() {
        val tasks = listOf(
            Task(1, "Отправить договор Марату", dueDate = "2026-11-26"),
            Task(2, "Позвонить Ивану", dueDate = "2026-11-26", dueTime = "10:00"),
            Task(3, "Счёт поставщику", dueDate = "2026-11-24"),
            Task(4, "Давно", dueDate = "2026-11-01"),
            Task(5, "Завтра", dueDate = "2026-11-27"),
            Task(6, "Закрыта", dueDate = "2026-11-26", done = 1),
        )
        val (seg, late) = Plan.digest(tasks, today)
        assertEquals(listOf("10:00 — Позвонить Ивану", "Отправить договор Марату"), seg)
        assertEquals(listOf("Счёт поставщику (срок 24 ноября)"), late)
    }
}

/** Подставные данные: экраны рендерятся без сети. */
private class Fake : Dannye {
    val done = mutableSetOf<Long>()
    val tasksList = listOf(
        Task(1, "Позвонить Ивану по накладным", dueDate = LocalDate.now(Vremya.ALMATY).toString(), dueTime = "10:00", recId = 3, title = "Итоги встречи: вывоз зерна", recorded = "2026-11-26T08:05:00"),
        Task(2, "Отправить договор Марату", dueDate = LocalDate.now(Vremya.ALMATY).minusDays(1).toString(), recId = 2, title = "Задача на завтра", recorded = "2026-11-25T00:11:45"),
        Task(3, "Купить картридж для конторы", recId = 3, title = "Итоги встречи: вывоз зерна", recorded = "2026-11-26T08:05:00"),
        Task(4, "Проверить оплату от клиента", dueDate = LocalDate.now(Vremya.ALMATY).plusDays(3).toString(), recId = 3, title = "Итоги встречи", recorded = "2026-11-26T08:05:00"),
    )
    override suspend fun recs(q: String, offset: Int) = RecList(listOf(
        Rec(3, LocalDate.now(Vremya.ALMATY).toString() + "T18:05:00", 263.0, "Итоги встречи: вывоз зерна, настройка CRM, закупка тонера", "Договорились с Иваном об отправке двух машин за остатками зерна. По второму вопросу нужно до пятницы настроить курьера.", "done", 420, 3),
        Rec(2, LocalDate.now(Vremya.ALMATY).toString() + "T00:11:45", 8.3, "Задача на завтра, 26 число, отправить договор Марату.", "Задача на завтра, 26 число, отправить договор Марату.", "done", 8, 1),
        Rec(1, "2026-11-23T14:20:00", 1840.0, "Планёрка по уборке урожая", "Обсудили график техники на северном участке.", "working", 0, 0),
    ))
    override suspend fun rec(id: Long) = RecFull(
        3, "2026-11-26T18:05:00", 263.0, "Итоги встречи: вывоз зерна, настройка CRM, закупка тонера", "done", 420,
        summary = "Тема: Итоги встречи\n\nКоротко: Договорились с Иваном об отправке двух машин за остатками зерна. В конторе закончился картридж.\n\nРешения:\n- Завтра к 9:00 две машины на поле «Северное»\n\nЦифры и факты:\n- Смета не ниже 200 000 ₸",
        transcript = "[00:00] Так, записываю для себя итоги встречи. Договорились с Иваном, что завтра к 9 утра отправляем две машины.\n[00:40] И не забыть, в понедельник созвон с клиентом по боту для карго.\n[01:13] Ещё надо позвонить поставщику тонера.",
        hasAudio = false, tasks = tasksList.take(2),
    )
    override suspend fun tasks(done: Boolean) = if (done) emptyList() else tasksList
    override suspend fun setDone(id: Long, done: Boolean) { if (done) this.done += id }
    override suspend fun rename(id: Long, title: String) {}
    override suspend fun delete(id: Long) {}
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class EkranyTest {
    @get:Rule val rule = createComposeRule()

    private fun show(dark: Boolean = false, tab: String = "recs", rec: Long? = null, fake: Fake = Fake()) {
        val nav = Nav().apply { this.tab = tab; recId = rec }
        rule.setContent {
            DiktofonTheme(dark = dark) {
                Box(Modifier.fillMaxSize()) {
                    MainScaffold(nav, fake, player = { r, e -> RecBody(r, fake, null, e) {} }, onAuthLost = {}, onTasksChanged = {}, openTasks = 4)
                }
            }
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
    }

    @Test fun zapisi() {
        show()
        rule.onNodeWithText("Итоги встречи: вывоз зерна, настройка CRM, закупка тонера").assertExists()
        rule.onNodeWithText("обрабатывается").assertExists()
        rule.onRoot().captureRoboImage("build/shots/1-zapisi.png")
    }

    @Test fun zapisiTemnaya() {
        show(dark = true)
        rule.onRoot().captureRoboImage("build/shots/1-zapisi-dark.png")
    }

    @Test fun kartochka() {
        show(rec = 3)
        rule.onNodeWithText("Коротко".uppercase()).assertExists()
        rule.onNodeWithText("Позвонить Ивану по накладным").assertExists()
        rule.onRoot().captureRoboImage("build/shots/2-zapis.png")
    }

    @Test fun zadachi() {
        val f = Fake()
        show(tab = "tasks", fake = f)
        rule.onNodeWithText("Отправить договор Марату").assertExists()
        rule.onRoot().captureRoboImage("build/shots/3-zadachi.png")
        rule.onNodeWithText("Позвонить Ивану по накладным").assertExists()
    }

    @Test fun vhod() {
        rule.setContent { DiktofonTheme { LoginScreen(onLogin = {}) } }
        rule.onNodeWithText("Войти").assertExists()
        rule.onRoot().captureRoboImage("build/shots/0-vhod.png")
    }
}
