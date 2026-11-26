package kz.damird.watchrec

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

/**
 * Один экран: большая кнопка старт/стоп, таймер, сколько файлов ждёт отправки.
 * Долгое нажатие на кнопку — отправить очередь прямо сейчас.
 *
 * Запуск из плитки (extra "toggle") переключает запись и сразу закрывается.
 * Запуск иконкой/кнопкой часов при AUTO_START=true сразу начинает запись.
 */
class MainActivity : Activity() {

    companion object {
        const val EXTRA_TOGGLE = "toggle"
        private const val REQ_PERMS = 1
    }

    private lateinit var btn: Button
    private lateinit var timer: TextView
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var pendingStart = false
    private var finishAfterAction = false

    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        btn = findViewById(R.id.btn)
        timer = findViewById(R.id.timer)
        status = findViewById(R.id.status)

        btn.setOnClickListener { toggle() }
        btn.setOnLongClickListener {
            UploadWorker.enqueue(this)
            Toast.makeText(this, R.string.sending, Toast.LENGTH_SHORT).show()
            true
        }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        if (i.getBooleanExtra(EXTRA_TOGGLE, false)) {
            finishAfterAction = true
            toggle()
        } else if (BuildConfig.AUTO_START && i.action == Intent.ACTION_MAIN && !RecState.recording) {
            start()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun toggle() {
        if (RecState.recording) stop() else start()
    }

    private fun start() {
        val need = buildList {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isNotEmpty()) {
            pendingStart = true
            requestPermissions(need.toTypedArray(), REQ_PERMS)
            return
        }
        RecorderService.start(this)
        maybeFinish()
    }

    private fun stop() {
        RecorderService.stop(this)
        maybeFinish()
    }

    private fun maybeFinish() {
        if (finishAfterAction) {
            finishAfterAction = false
            // даём сервису стартовать, пока экран на переднем плане (требование Android 14 для микрофона)
            handler.postDelayed({ finish() }, 700)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (pendingStart && micOk) {
            pendingStart = false
            RecorderService.start(this)
            maybeFinish()
        } else if (!micOk) {
            Toast.makeText(this, R.string.need_mic, Toast.LENGTH_LONG).show()
        }
    }

    private fun render() {
        val queued = RecState.pendingFiles(this).size
        if (RecState.recording) {
            btn.text = "■"
            btn.setBackgroundResource(R.drawable.btn_stop)
            timer.text = RecState.format(RecState.elapsedSec())
            status.text = getString(R.string.recording_now)
        } else {
            btn.text = "●"
            btn.setBackgroundResource(R.drawable.btn_rec)
            timer.text = ""
            status.text = if (queued > 0) getString(R.string.queued, queued) else getString(R.string.ready)
        }
    }
}
