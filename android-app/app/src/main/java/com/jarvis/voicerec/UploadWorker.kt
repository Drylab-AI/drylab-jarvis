package com.jarvis.voicerec

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Uploads one clip with a multipart POST. Deletes the local file only after a
 * 2xx response. On a network/5xx failure it returns retry() so WorkManager
 * re-runs it later with exponential backoff.
 */
class UploadWorker(ctx: Context, params: WorkerParameters) :
    CoroutineWorker(ctx, params) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.MINUTES)   // long clips over slow Wi-Fi
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val path = inputData.getString(UploadQueue.KEY_PATH)
            ?: return@withContext Result.failure()
        val startedAt = inputData.getString(UploadQueue.KEY_STARTED_AT) ?: ""
        val durationMs = inputData.getLong(UploadQueue.KEY_DURATION_MS, 0)
        val serverUrl = inputData.getString(UploadQueue.KEY_SERVER_URL)
            ?: Prefs.serverUrl(applicationContext)

        val file = File(path)
        if (!file.exists()) {
            // Already uploaded and deleted on a previous run. Nothing to do.
            return@withContext Result.success()
        }

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("device", android.os.Build.MODEL ?: "pixel")
            .addFormDataPart("started_at", startedAt)
            .addFormDataPart("duration_ms", durationMs.toString())
            .addFormDataPart(
                "file",
                file.name,
                file.asRequestBody("audio/mp4".toMediaType()),
            )
            .build()

        val request = Request.Builder()
            .url("$serverUrl/upload")
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { resp ->
                when {
                    resp.isSuccessful -> {
                        file.delete()
                        Log.i("UploadWorker", "uploaded ${file.name}")
                        Result.success()
                    }
                    // 4xx (except 408/429) means the server rejected it; do not
                    // loop forever on a request it will never accept.
                    resp.code in 400..499 && resp.code != 408 && resp.code != 429 -> {
                        Log.e("UploadWorker", "rejected ${resp.code}; giving up on ${file.name}")
                        Result.failure()
                    }
                    else -> {
                        Log.w("UploadWorker", "server ${resp.code}; will retry ${file.name}")
                        Result.retry()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("UploadWorker", "network error; will retry ${file.name}: ${e.message}")
            Result.retry()
        }
    }
}
