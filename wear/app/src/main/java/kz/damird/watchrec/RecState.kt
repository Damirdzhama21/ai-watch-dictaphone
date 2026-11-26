package kz.damird.watchrec

import android.content.Context
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.wear.tiles.TileService
import java.io.File

/** Общее состояние записи (один процесс — хватает простого синглтона). */
object RecState {
    @Volatile var recording: Boolean = false
    @Volatile var startedElapsed: Long = 0L

    /** идущая запись пишется сюда — загрузчик её не видит */
    fun recordingDir(ctx: Context) = File(ctx.filesDir, "recording").apply { mkdirs() }

    /** готовые к отправке */
    fun pendingDir(ctx: Context) = File(ctx.filesDir, "pending").apply { mkdirs() }

    fun pendingFiles(ctx: Context): List<File> =
        pendingDir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".ogg") }
            ?.sortedBy { it.name } ?: emptyList()

    fun elapsedSec(): Long =
        if (recording) (SystemClock.elapsedRealtime() - startedElapsed) / 1000 else 0

    fun format(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    @Suppress("DEPRECATION")
    fun vibrate(ctx: Context, ms: Long) {
        val v = ctx.getSystemService(Vibrator::class.java) ?: return
        v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    fun refreshTile(ctx: Context) {
        try {
            TileService.getUpdater(ctx).requestUpdate(RecTileService::class.java)
        } catch (_: Exception) {
        }
    }
}
