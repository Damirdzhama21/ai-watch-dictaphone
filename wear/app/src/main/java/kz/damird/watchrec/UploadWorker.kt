package kz.damird.watchrec

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Отправляет готовые записи на сервер. Работает через WorkManager:
 * нет сети — ждёт, ошибка — повторит позже с нарастающей паузой.
 * В интернет часы ходят через телефон (Bluetooth) или напрямую по Wi-Fi.
 */
class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        private const val TAG = "WatchRec"
        private const val WORK_NAME = "upload"

        fun enqueue(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }

        private val client by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.MINUTES)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val files = RecState.pendingFiles(applicationContext)
        for (f in files) {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("device", Build.MODEL ?: "watch")
                .addFormDataPart("file", f.name, f.asRequestBody("audio/ogg".toMediaType()))
                .build()
            val req = Request.Builder()
                .url(BuildConfig.SERVER_URL)
                .header("Authorization", "Bearer ${BuildConfig.UPLOAD_TOKEN}")
                .post(body)
                .build()
            try {
                client.newCall(req).execute().use { resp ->
                    when {
                        resp.isSuccessful -> {
                            Log.i(TAG, "uploaded ${f.name}")
                            f.delete()
                        }
                        resp.code == 401 || resp.code == 403 -> {
                            Log.e(TAG, "auth error ${resp.code}")
                            return@withContext Result.failure() // неверный токен: файл остаётся
                        }
                        else -> {
                            Log.w(TAG, "server ${resp.code}, retry")
                            return@withContext Result.retry()
                        }
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "network error, retry", e)
                return@withContext Result.retry()
            }
        }
        Result.success()
    }
}
