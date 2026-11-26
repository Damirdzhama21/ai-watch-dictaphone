package kz.damird.diktofon.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kz.damird.diktofon.RecFull
import kz.damird.diktofon.Vremya

/** Состояние плеера, общее для полосы и расшифровки (подсветка абзаца, перемотка по метке). */
interface PlayerState {
    val posSec: Double
    fun seek(sec: Int)
}

/** MediaPlayer с заголовком авторизации; сервер отдаёт AAC с поддержкой Range — перемотка без докачки всего файла. */
class Pleer(private val ctx: Context, private val url: String, private val headers: Map<String, String>, val durSec: Double) : PlayerState {
    private var mp: MediaPlayer? = null
    private var prepared = false
    private var pendingSeek: Int? = null
    private var playWhenReady = false

    override var posSec by mutableDoubleStateOf(0.0); private set
    var playing by mutableStateOf(false); private set
    var loading by mutableStateOf(false); private set
    var speed by mutableFloatStateOf(1f); private set
    var error by mutableStateOf<String?>(null); private set

    private fun ensure() {
        if (mp != null) return
        loading = true
        mp = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            setDataSource(ctx, Uri.parse(url), headers)
            setOnPreparedListener {
                prepared = true; loading = false
                pendingSeek?.let { s -> it.seekTo(s * 1000) }; pendingSeek = null
                if (playWhenReady) start0()
            }
            setOnCompletionListener { playing = false; posSec = 0.0 }
            setOnErrorListener { _, what, _ -> error = "Не играет ($what)"; loading = false; playing = false; true }
            prepareAsync()
        }
    }

    private fun start0() {
        val p = mp ?: return
        runCatching { p.playbackParams = p.playbackParams.setSpeed(speed) }
        p.start(); playing = true
    }

    fun toggle() {
        if (playing) { mp?.pause(); playing = false; return }
        playWhenReady = true
        if (prepared) start0() else ensure()
    }

    override fun seek(sec: Int) {
        posSec = sec.toDouble()
        playWhenReady = true
        if (prepared) { mp?.seekTo(sec * 1000); if (!playing) start0() } else { pendingSeek = sec; ensure() }
    }

    fun seekTo(sec: Double) {
        posSec = sec
        if (prepared) mp?.seekTo((sec * 1000).toInt()) else { pendingSeek = sec.toInt() }
    }

    fun nextSpeed() {
        speed = when (speed) { 1f -> 1.5f; 1.5f -> 2f; else -> 1f }
        if (playing) runCatching { mp?.let { it.playbackParams = it.playbackParams.setSpeed(speed) } }
    }

    fun tick() { if (playing) mp?.let { posSec = it.currentPosition / 1000.0 } }

    fun release() { runCatching { mp?.release() }; mp = null; prepared = false; playing = false }
}

@Composable
fun PlayerAndBody(rec: RecFull, url: String, headers: Map<String, String>, body: @Composable (PlayerState) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val p = remember(rec.id) { Pleer(ctx.applicationContext, url, headers, rec.duration) }
    DisposableEffect(p) { onDispose { p.release() } }
    LaunchedEffect(p.playing) { while (p.playing) { p.tick(); delay(250) } }
    Column {
        PlayerBar(p)
        body(p)
    }
}

@Composable
fun PlayerBar(p: Pleer) {
    val c = cv()
    var drag by remember { mutableStateOf<Float?>(null) }
    val dur = p.durSec.coerceAtLeast(1.0)
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.card)
            .border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(start = 10.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(46.dp).clip(CircleShape).background(c.accent).clickable { p.toggle() }, contentAlignment = Alignment.Center) {
            Icon(if (p.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (p.playing) "Пауза" else "Играть", tint = Color.White)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Slider(
                value = drag ?: (p.posSec / dur).toFloat().coerceIn(0f, 1f),
                onValueChange = { drag = it },
                onValueChangeFinished = { drag?.let { p.seekTo(it * dur) }; drag = null },
                colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.line),
                modifier = Modifier.height(28.dp),
            )
            Row {
                Text(Vremya.dur(drag?.let { it * dur } ?: p.posSec), style = T.meta, color = c.muted)
                Spacer(Modifier.weight(1f))
                Text(p.error ?: if (p.loading) "загрузка…" else Vremya.dur(p.durSec), style = T.meta, color = if (p.error != null) c.accent else c.muted)
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            (if (p.speed == 1f) "1" else if (p.speed == 1.5f) "1,5" else "2") + "×", style = T.meta.copy(fontWeight = FontWeight.SemiBold), color = c.ink,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(c.bg).border(1.dp, c.line, RoundedCornerShape(8.dp))
                .clickable { p.nextSpeed() }.padding(horizontal = 9.dp, vertical = 5.dp),
        )
    }
}
