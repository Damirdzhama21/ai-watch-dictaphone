package kz.damird.diktofon

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kz.damird.diktofon.ui.*

class MainActivity : ComponentActivity() {
    private val nav = Nav()
    private lateinit var api: Api
    private var loggedIn by mutableStateOf(false)
    private var openTasks by mutableIntStateOf(0)

    private val askNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        api = Api(this)
        loggedIn = api.loggedIn
        handle(intent)
        if (loggedIn) afterLogin()

        setContent {
            DiktofonTheme {
                if (!loggedIn) {
                    LoginScreen(onLogin = { pw -> api.login(pw); loggedIn = true; afterLogin() })
                } else {
                    val d = remember { SetevyeDannye(api) }
                    MainScaffold(
                        nav = nav, d = d, openTasks = openTasks,
                        player = { rec, onErr ->
                            PlayerAndBody(rec, api.audioUrl(rec.id), api.audioHeaders) { pl ->
                                RecBody(rec, d, pl, onErr = onErr, onTasksChanged = ::tasksChanged)
                            }
                        },
                        onAuthLost = { api.logout(); loggedIn = false },
                        onTasksChanged = ::tasksChanged,
                    )
                }
            }
        }
    }

    private fun afterLogin() {
        if (Build.VERSION.SDK_INT >= 33) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        Napominaniya.start(this)
        tasksChanged()
    }

    /** Задачу закрыли/открыли в приложении — счётчик на вкладке и будильники должны это знать. */
    private fun tasksChanged() {
        lifecycleScope.launch {
            Napominaniya.sync(this@MainActivity)
            openTasks = TaskCache.load(this@MainActivity).size
        }
    }

    override fun onResume() {
        super.onResume()
        if (loggedIn) tasksChanged()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** Уведомление → «Задачи»; ссылка https://<сервер>/#/r/42 из Telegram → сразу запись. */
    private fun handle(i: Intent?) {
        i ?: return
        if (i.getStringExtra("screen") == "tasks") { nav.recId = null; nav.tab = "tasks" }
        i.getLongExtra("rec", 0L).takeIf { it > 0 }?.let { nav.recId = it }
        val frag = i.data?.fragment.orEmpty()
        Regex("""^/r/(\d+)""").find(frag)?.let { nav.recId = it.groupValues[1].toLong() }
        if (frag.startsWith("/tasks")) { nav.recId = null; nav.tab = "tasks" }
    }
}
