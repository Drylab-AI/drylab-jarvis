package com.jarvis.voicerec

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.io.File
import java.util.concurrent.TimeUnit

/** Builds and enqueues an [UploadWorker] for one recorded clip. */
object UploadQueue {
    const val KEY_PATH = "path"
    const val KEY_STARTED_AT = "started_at"
    const val KEY_DURATION_MS = "duration_ms"
    const val KEY_SERVER_URL = "server_url"

    fun enqueue(ctx: Context, file: File, startedAt: String, durationMs: Long) {
        val data = Data.Builder()
            .putString(KEY_PATH, file.absolutePath)
            .putString(KEY_STARTED_AT, startedAt)
            .putLong(KEY_DURATION_MS, durationMs)
            .putString(KEY_SERVER_URL, Prefs.serverUrl(ctx))
            .build()

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(data)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .addTag("jarvis-upload")
            .build()

        // Unique by filename so re-enqueues of the same clip do not pile up.
        WorkManager.getInstance(ctx)
            .enqueueUniqueWork("upload-${file.name}", ExistingWorkPolicy.KEEP, req)
    }
}
