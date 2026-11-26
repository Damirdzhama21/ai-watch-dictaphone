package kz.damird.diktofon

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Напоминания на телефоне (и на часах — Wear OS сам зеркалит уведомления телефона вместе с кнопками).
 * Задачи берутся с сервера и кэшируются: будильники ставятся локально, поэтому срабатывают и без сети.
 */
object Napominaniya {
    private const val TAG = "diktofon"
    const val CH_REMIND = "napominaniya"
    const val CH_DIGEST = "svodka"
    const val CH_NEW = "novye"
    private const val DIGEST_ID = 900_000_001
    private const val SNOOZE_MIN = 60L

    const val ACT_REMIND = "kz.damird.diktofon.REMIND"
    const val ACT_DIGEST = "kz.damird.diktofon.DIGEST"
    const val ACT_DONE = "kz.damird.diktofon.DONE"
    const val ACT_SNOOZE = "kz.damird.diktofon.SNOOZE"
    const val EX_ID = "id"
    const val EX_TEXT = "text"
    const val EX_TIME = "time"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_REMIND, "Напоминания о задачах", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Ко времени, названному в записи"
        })
        nm.createNotificationChannel(NotificationChannel(CH_NEW, "Новые записи", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Запись с часов расшифрована: тема и задачи"
        })
        nm.createNotificationChannel(NotificationChannel(CH_DIGEST, "Утренняя сводка", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "В 9:00 — задачи на сегодня и просроченные"
        })
    }

    /** Подтянуть задачи с сервера и переставить будильники. Без сети — по кэшу. */
    suspend fun sync(ctx: Context, notifyNew: Boolean = false): Boolean {
        val api = Api(ctx)
        if (!api.loggedIn) return false
        val ok = runCatching { TaskCache.save(ctx, api.tasks(done = false)) }
            .onFailure { Log.w(TAG, "sync: ${it.message}") }.isSuccess
        runCatching { checkNew(ctx, api, notifyNew) }.onFailure { Log.w(TAG, "new: ${it.message}") }
        reschedule(ctx)
        return ok
    }

    /**
     * Новые расшифрованные записи → уведомление «🎙 тема» + задачи. Из фона (worker) — показываем,
     * при открытом приложении — только отмечаем увиденными. Первый запуск лишь запоминает текущие.
     */
    private suspend fun checkNew(ctx: Context, api: Api, notify: Boolean) {
        val prefs = ctx.getSharedPreferences("diktofon", Context.MODE_PRIVATE)
        val recs = api.recs("", 0).items.filter { it.status == "done" }
        val seen = prefs.getStringSet("seen_recs", null)
        val ids = recs.map { it.id.toString() }.toSet()
        if (seen == null) { prefs.edit().putStringSet("seen_recs", ids).apply(); return }
        val fresh = recs.filter { it.id.toString() !in seen }
        if (notify && canNotify(ctx)) fresh.take(5).forEach { showNew(ctx, it) }
        prefs.edit().putStringSet("seen_recs", (seen + ids).toList().takeLast(300).toSet()).apply()
    }

    private fun showNew(ctx: Context, r: Rec) {
        val tasks = TaskCache.load(ctx).filter { it.recId == r.id }
        val body = buildString {
            if (r.preview.isNotBlank()) append(r.preview)
            if (tasks.isNotEmpty()) {
                append("\n\nЗадачи:")
                tasks.forEach { t -> append("\n• ").append(t.text); Vremya.srok(t.dueDate, t.dueTime)?.let { append(" — ").append(it.text) } }
            }
        }
        val pi = PendingIntent.getActivity(
            ctx, (r.id * 4 + 900).toInt(),
            Intent(ctx, MainActivity::class.java).putExtra("rec", r.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CH_NEW)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("🎙 " + r.title.ifBlank { "Новая запись" })
            .setContentText(r.preview.ifBlank { Vremya.durWords(r.duration) })
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.ifBlank { Vremya.durWords(r.duration) }))
            .setContentIntent(pi).setAutoCancel(true).build()
        runCatching { NotificationManagerCompat.from(ctx).notify(800_000_000 + r.id.toInt(), n) }
    }

    fun reschedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val prefs = ctx.getSharedPreferences("diktofon", Context.MODE_PRIVATE)
        // снять старые будильники задач (закрытые/перенесённые не должны звенеть)
        prefs.getStringSet("alarm_ids", emptySet())!!.forEach { id ->
            am.cancel(remindPi(ctx, id.toLong(), "", ""))
        }
        val ids = mutableSetOf<String>()
        for (t in TaskCache.load(ctx)) {
            val at = Plan.remindAt(t) ?: continue
            setAlarm(ctx, at, remindPi(ctx, t.id, t.text, t.dueTime.orEmpty()))
            ids += t.id.toString()
        }
        prefs.edit().putStringSet("alarm_ids", ids).apply()
        setAlarm(ctx, Plan.nextDigest(), digestPi(ctx))
        Log.i(TAG, "будильников задач: ${ids.size}")
    }

    private fun setAlarm(ctx: Context, at: Long, pi: PendingIntent) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    private fun remindPi(ctx: Context, id: Long, text: String, time: String): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, id.toInt(),
            Intent(ctx, AlarmReceiver::class.java).setAction(ACT_REMIND)
                .putExtra(EX_ID, id).putExtra(EX_TEXT, text).putExtra(EX_TIME, time),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun digestPi(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, DIGEST_ID, Intent(ctx, AlarmReceiver::class.java).setAction(ACT_DIGEST),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun openPi(ctx: Context, screen: String, req: Int): PendingIntent =
        PendingIntent.getActivity(
            ctx, req,
            Intent(ctx, MainActivity::class.java).putExtra("screen", screen)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun canNotify(ctx: Context) =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun showReminder(ctx: Context, id: Long, text: String, time: String) {
        if (!canNotify(ctx)) return
        fun act(action: String, req: Int) = PendingIntent.getBroadcast(
            ctx, req, Intent(ctx, ActionReceiver::class.java).setAction(action)
                .putExtra(EX_ID, id).putExtra(EX_TEXT, text).putExtra(EX_TIME, time),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CH_REMIND)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(if (time.isNotBlank()) "⏰ $time" else "Напоминание")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openPi(ctx, "tasks", 1))
            .setAutoCancel(true)
            .addAction(R.drawable.ic_check, "Сделано", act(ACT_DONE, (id * 4 + 1).toInt()))
            .addAction(0, "Через час", act(ACT_SNOOZE, (id * 4 + 2).toInt()))
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(id.toInt(), n) }
    }

    fun showDigest(ctx: Context, tasks: List<Task>) {
        if (!canNotify(ctx)) return
        val (seg, late) = Plan.digest(tasks)
        if (seg.isEmpty() && late.isEmpty()) return
        val title = when {
            seg.isNotEmpty() -> "На сегодня: " + Vremya.plural(seg.size, "задача", "задачи", "задач")
            else -> "Просрочено: " + Vremya.plural(late.size, "задача", "задачи", "задач")
        }
        val style = NotificationCompat.InboxStyle()
        seg.take(6).forEach { style.addLine("• $it") }
        if (late.isNotEmpty()) late.take(4).forEach { style.addLine("⚠ $it") }
        val n = NotificationCompat.Builder(ctx, CH_DIGEST)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText((seg + late).first())
            .setStyle(style)
            .setContentIntent(openPi(ctx, "tasks", 2))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(DIGEST_ID, n) }
    }

    fun start(ctx: Context) {
        channels(ctx)
        val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            "sync", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(net).build(),
        )
        scope.launch { sync(ctx) }
    }

    /** Закрыть задачу из уведомления: сразу локально, на сервер — когда будет сеть. */
    fun markDone(ctx: Context, id: Long) {
        NotificationManagerCompat.from(ctx).cancel(id.toInt())
        TaskCache.save(ctx, TaskCache.load(ctx).filter { it.id != id })
        val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "done-$id", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<DoneWorker>().setConstraints(net).setInputData(workDataOf(EX_ID to id)).build(),
        )
    }

    class AlarmReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACT_REMIND -> {
                    val id = intent.getLongExtra(EX_ID, 0)
                    // задачу могли закрыть в приложении/вебе, а кэш ещё не обновился — сверяемся с кэшем
                    if (TaskCache.load(ctx).any { it.id == id } || intent.getBooleanExtra("snoozed", false)) {
                        showReminder(ctx, id, intent.getStringExtra(EX_TEXT).orEmpty(), intent.getStringExtra(EX_TIME).orEmpty())
                    }
                }
                ACT_DIGEST -> {
                    val pr = goAsync()
                    scope.launch {
                        try {
                            sync(ctx)                      // свежие задачи, если есть сеть; иначе кэш
                            showDigest(ctx, TaskCache.load(ctx))
                        } finally {
                            setAlarm(ctx, Plan.nextDigest(), digestPi(ctx))
                            pr.finish()
                        }
                    }
                }
            }
        }
    }

    class ActionReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val id = intent.getLongExtra(EX_ID, 0)
            when (intent.action) {
                ACT_DONE -> markDone(ctx, id)
                ACT_SNOOZE -> {
                    NotificationManagerCompat.from(ctx).cancel(id.toInt())
                    // свой requestCode: пересчёт будильников задач (reschedule) не должен снять отложенное
                    val pi = PendingIntent.getBroadcast(
                        ctx, (id * 4 + 3).toInt(),
                        Intent(ctx, AlarmReceiver::class.java).setAction(ACT_REMIND).putExtra("snoozed", true)
                            .putExtra(EX_ID, id).putExtra(EX_TEXT, intent.getStringExtra(EX_TEXT))
                            .putExtra(EX_TIME, intent.getStringExtra(EX_TIME)),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    setAlarm(ctx, System.currentTimeMillis() + SNOOZE_MIN * 60_000, pi)
                }
            }
        }
    }

    class BootReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            reschedule(ctx)
            start(ctx)
        }
    }
}

class SyncWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result = if (Napominaniya.sync(applicationContext, notifyNew = true)) Result.success() else Result.retry()
}

class DoneWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong(Napominaniya.EX_ID, 0)
        return runCatching { Api(applicationContext).setDone(id, true) }
            .fold({ Result.success() }, { if (it is AuthException) Result.failure() else Result.retry() })
    }
}
