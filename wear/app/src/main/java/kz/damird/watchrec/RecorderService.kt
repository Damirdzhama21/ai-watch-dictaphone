package kz.damird.watchrec

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Фоновая запись с микрофона часов. Работает при погашенном экране и
 * в других приложениях. Файл: OGG/Opus, 16 кГц моно, 24 кбит/с (~11 МБ/час).
 */
class RecorderService : Service() {

    companion object {
        private const val TAG = "WatchRec"
        const val ACTION_START = "kz.damird.watchrec.START"
        const val ACTION_STOP = "kz.damird.watchrec.STOP"
        private const val CHANNEL = "rec"
        private const val NOTIF_ID = 1
        private const val MAX_DURATION_MS = 4 * 3600 * 1000 // 4 часа на файл

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, RecorderService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, RecorderService::class.java).setAction(ACTION_STOP))
        }
    }

    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (recorder != null) stopRecording()
        super.onDestroy()
    }

    private fun makeRecorder(source: Int, file: File): MediaRecorder {
        @Suppress("DEPRECATION")
        val base = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
        return base.apply {
            setAudioSource(source)
            setOutputFormat(MediaRecorder.OutputFormat.OGG)
            setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            setAudioChannels(1)
            setAudioSamplingRate(48000)
            setAudioEncodingBitRate(48000)   // ~21 МБ/час — укладывается в лимит API кусками по 5 мин
            setMaxDuration(MAX_DURATION_MS)
            setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) stopRecording()
            }
            setOutputFile(file.absolutePath)
            try { prepare() } catch (e: Exception) { release(); throw e }
        }
    }

    private fun startRecording() {
        if (recorder != null) return
        recoverLeftovers()
        startForegroundNow()

        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(RecState.recordingDir(this), "rec_$ts.ogg")
        try {
            // VOICE_COMMUNICATION: система включает автоусиление и шумодав — микрофон часов на MIC пишет
            // слишком тихо (средний уровень −45…−50 дБ), речь теряется. Не поддерживается — откат на MIC.
            val r = try {
                makeRecorder(MediaRecorder.AudioSource.VOICE_COMMUNICATION, file)
            } catch (e: Exception) {
                Log.w("watchrec", "VOICE_COMMUNICATION недоступен, пишу с MIC: ${e.message}")
                file.delete()
                makeRecorder(MediaRecorder.AudioSource.MIC, file)
            }
            r.start()
            recorder = r
            currentFile = file
            RecState.startedElapsed = SystemClock.elapsedRealtime()
            RecState.recording = true
            acquireWakeLock()
            RecState.vibrate(this, 80)
            RecState.refreshTile(this)
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            Toast.makeText(this, "Ошибка микрофона: ${e.message}", Toast.LENGTH_LONG).show()
            file.delete()
            cleanupAndStop()
        }
    }

    private fun stopRecording() {
        val r = recorder ?: run { cleanupAndStop(); return }
        val file = currentFile
        recorder = null
        currentFile = null
        var ok = true
        try {
            r.stop()
        } catch (e: RuntimeException) {
            // слишком короткая запись — файл битый
            ok = false
        } finally {
            r.release()
        }
        if (file != null) {
            if (ok && file.length() > 0) {
                file.renameTo(File(RecState.pendingDir(this), file.name))
            } else {
                file.delete()
            }
        }
        RecState.recording = false
        RecState.vibrate(this, 40)
        UploadWorker.enqueue(this)
        cleanupAndStop()
    }

    /** если процесс убили посреди записи — забираем то, что успело записаться */
    private fun recoverLeftovers() {
        RecState.recordingDir(this).listFiles()?.forEach { f ->
            if (f.length() > 4096) f.renameTo(File(RecState.pendingDir(this), f.name)) else f.delete()
        }
    }

    private fun startForegroundNow() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val nb = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.recording_now))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis())
            .setContentIntent(open)

        // значок записи на циферблате и в списке "текущих действий" Wear OS
        OngoingActivity.Builder(applicationContext, NOTIF_ID, nb)
            .setStaticIcon(R.drawable.ic_mic)
            .setTouchIntent(open)
            .setStatus(Status.Builder().addTemplate(getString(R.string.recording_now)).build())
            .build()
            .apply(applicationContext)

        ServiceCompat.startForeground(
            this, NOTIF_ID, nb.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WatchRec:rec").apply {
            setReferenceCounted(false)
            acquire(MAX_DURATION_MS.toLong() + 60_000L)
        }
    }

    private fun cleanupAndStop() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        RecState.recording = false
        RecState.refreshTile(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
