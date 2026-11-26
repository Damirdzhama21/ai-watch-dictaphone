package kz.damird.diktofon

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

@Serializable
data class Rec(
    val id: Long,
    val recorded: String = "",
    val duration: Double = 0.0,
    val title: String = "",
    val preview: String = "",
    val status: String = "done",
    val words: Int = 0,
    @SerialName("tasks_open") val tasksOpen: Int = 0,
)

@Serializable
data class RecList(val items: List<Rec> = emptyList(), val more: Boolean = false)

@Serializable
data class Task(
    val id: Long,
    val text: String,
    val done: Int = 0,
    @SerialName("due_date") val dueDate: String? = null,
    @SerialName("due_time") val dueTime: String? = null,
    @SerialName("rec_id") val recId: Long? = null,
    val title: String? = null,
    val recorded: String? = null,
)

@Serializable
data class TaskList(val items: List<Task> = emptyList())

@Serializable
data class RecFull(
    val id: Long,
    val recorded: String = "",
    val duration: Double = 0.0,
    val title: String = "",
    val status: String = "done",
    val words: Int = 0,
    val summary: String = "",
    val transcript: String = "",
    val error: String = "",
    @SerialName("has_audio") val hasAudio: Boolean = false,
    val tasks: List<Task> = emptyList(),
)

@Serializable
private data class LoginResp(val ok: Boolean = false, val token: String = "", val expires: Long = 0)

@Serializable
private data class Err(val detail: String = "")

class AuthException : IOException("нужен вход")
class ApiException(msg: String) : IOException(msg)

val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

/** Клиент API сервера диктофона. Токен сессии — в приватных настройках приложения. */
class Api(private val ctx: Context) {
    private val base = BuildConfig.BASE_URL
    private val prefs = ctx.getSharedPreferences("diktofon", Context.MODE_PRIVATE)

    var token: String?
        get() = prefs.getString("token", null)
        set(v) { prefs.edit().apply { if (v == null) remove("token") else putString("token", v) }.apply() }

    val loggedIn get() = !token.isNullOrBlank()
    val audioHeaders get() = mapOf("Authorization" to "Bearer ${token.orEmpty()}")
    fun audioUrl(id: Long) = "$base/api/recs/$id/audio"

    private suspend fun call(method: String, path: String, body: String? = null): String = withContext(Dispatchers.IO) {
        val rb = body?.toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(base + path).method(method, rb).apply {
            token?.let { header("Authorization", "Bearer $it") }
        }.build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (r.code == 401 && path != "/api/login") { token = null; throw AuthException() }
            if (!r.isSuccessful) {
                val d = runCatching { json.decodeFromString(Err.serializer(), text).detail }.getOrNull()
                throw ApiException(d?.takeIf { it.isNotBlank() } ?: "Ошибка сервера ${r.code}")
            }
            text
        }
    }

    suspend fun login(password: String) {
        val body = json.encodeToString(Pwd.serializer(), Pwd(password))
        val r = json.decodeFromString(LoginResp.serializer(), call("POST", "/api/login", body))
        if (r.token.isBlank()) throw ApiException("Сервер не выдал токен")
        token = r.token
    }

    fun logout() { token = null }

    suspend fun recs(q: String, offset: Int = 0): RecList =
        json.decodeFromString(RecList.serializer(), call("GET", "/api/recs?limit=40&offset=$offset&q=" + URLEncoder.encode(q, "UTF-8")))

    suspend fun rec(id: Long): RecFull = json.decodeFromString(RecFull.serializer(), call("GET", "/api/recs/$id"))

    suspend fun rename(id: Long, title: String) {
        call("PATCH", "/api/recs/$id", json.encodeToString(Title.serializer(), Title(title)))
    }

    suspend fun delete(id: Long) { call("DELETE", "/api/recs/$id") }

    suspend fun tasks(done: Boolean): List<Task> =
        json.decodeFromString(TaskList.serializer(), call("GET", "/api/tasks?done=${if (done) 1 else 0}")).items

    suspend fun setDone(id: Long, done: Boolean) {
        call("PATCH", "/api/tasks/$id", json.encodeToString(Done.serializer(), Done(done)))
    }

    @Serializable private data class Pwd(val password: String)
    @Serializable private data class Title(val title: String)
    @Serializable private data class Done(val done: Boolean)

    companion object {
        val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }
}

/** Кэш открытых задач: из него напоминания и утренняя сводка работают без сети. */
object TaskCache {
    fun save(ctx: Context, tasks: List<Task>) {
        ctx.getSharedPreferences("diktofon", Context.MODE_PRIVATE).edit()
            .putString("open_tasks", json.encodeToString(ListSerializer(Task.serializer()), tasks)).apply()
    }

    fun load(ctx: Context): List<Task> {
        val s = ctx.getSharedPreferences("diktofon", Context.MODE_PRIVATE).getString("open_tasks", null) ?: return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(Task.serializer()), s) }.getOrDefault(emptyList())
    }
}
