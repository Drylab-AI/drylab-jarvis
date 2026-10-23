package com.jarvis.voicerec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground microphone service. Press 1 starts recording, press 2 stops it,
 * saves the clip and queues the upload. It is driven by actions sent from
 * [ToggleActivity].
 */
class RecordService : Service() {

    companion object {
        const val ACTION_TOGGLE = "com.jarvis.voicerec.TOGGLE"
        const val ACTION_START = "com.jarvis.voicerec.START"
        const val ACTION_STOP = "com.jarvis.voicerec.STOP"

        private const val TAG = "RecordService"
        private const val CHANNEL_ID = "jarvis_recording"
        private const val NOTIF_ID = 1001

        // Safety cap: stop by itself after 2 hours.
        private const val MAX_DURATION_MS = 2 * 60 * 60 * 1000L

        @Volatile
        var isRecording: Boolean = false
            private set
    }

    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var startedAtMs: Long = 0L
    private var startedAtWall: String = ""
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stopAndQueue() }
    private val tick = object : Runnable {
        override fun run() {
            if (isRecording) {
                updateNotification()
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!isRecording) startRecording()
            ACTION_STOP -> if (isRecording) stopAndQueue()
            ACTION_TOGGLE, null -> if (isRecording) stopAndQueue() else startRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        // We must call startForeground quickly once started as a FGS.
        ensureChannel()
        startedAtMs = SystemClock.elapsedRealtime()
        startedAtWall = isoNow()
        startForegroundCompat()

        val dir = File(filesDir, "clips").apply { mkdirs() }
        val safeName = startedAtWall.replace(":", "-")
        val out = File(dir, "rec_$safeName.m4a")
        currentFile = out

        val rec = newRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44_100)
            setAudioChannels(1)
            setAudioEncodingBitRate(128_000)
            setOutputFile(out.absolutePath)
        }

        try {
            rec.prepare()
            rec.start()
        } catch (e: Exception) {
            // e.g. mic busy during a phone call. Fail cleanly, do not crash.
            Log.e(TAG, "start failed", e)
            safeReleaseRecorder(rec)
            out.delete()
            notifyError("Could not start recording (mic busy?)")
            stopSelf()
            return
        }

        recorder = rec
        isRecording = true
        acquireWakeLock()
        Vibrate.once(this)
        handler.postDelayed(autoStop, MAX_DURATION_MS)
        handler.post(tick)
        updateNotification()
        Log.i(TAG, "recording -> ${out.absolutePath}")
    }

    private fun stopAndQueue() {
        val rec = recorder
        val file = currentFile
        val durationMs = SystemClock.elapsedRealtime() - startedAtMs
        isRecording = false
        handler.removeCallbacks(autoStop)
        handler.removeCallbacks(tick)

        var ok = false
        if (rec != null) {
            try {
                rec.stop()
                ok = true
            } catch (e: Exception) {
                // stop() throws if almost no data was captured; drop the file.
                Log.e(TAG, "stop failed", e)
            } finally {
                safeReleaseRecorder(rec)
            }
        }
        recorder = null
        releaseWakeLock()
        Vibrate.twice(this)

        if (ok && file != null && file.exists() && file.length() > 0) {
            UploadQueue.enqueue(
                this,
                file = file,
                startedAt = startedAtWall,
                durationMs = durationMs,
            )
            Log.i(TAG, "queued upload ${file.name} (${file.length()} bytes)")
        } else {
            file?.delete()
            notifyError("Recording too short or failed")
        }

        currentFile = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- notifications -----------------------------------------------------

    private fun startForegroundCompat() {
        val n = buildNotification(elapsedSec = 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification() {
        val elapsed = ((SystemClock.elapsedRealtime() - startedAtMs) / 1000).toInt()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(elapsed))
    }

    private fun buildNotification(elapsedSec: Int): Notification {
        val mm = elapsedSec / 60
        val ss = elapsedSec % 60
        val stopIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, ToggleActivity::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording %02d:%02d".format(mm, ss))
            .setContentText("Press Volume Up again to stop")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(null, "Stop", stopIntent).build(),
            )
            .build()
    }

    private fun notifyError(msg: String) {
        ensureChannel()
        val nm = getSystemService(NotificationManager::class.java)
        val n = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Jarvis recorder")
            .setContentText(msg)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID + 1, n)
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Recording",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    // ---- helpers -----------------------------------------------------------

    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this)
        else @Suppress("DEPRECATION") MediaRecorder()

    private fun safeReleaseRecorder(rec: MediaRecorder) {
        try {
            rec.reset()
        } catch (_: Exception) {
        }
        try {
            rec.release()
        } catch (_: Exception) {
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:rec").apply {
            setReferenceCounted(false)
            acquire(MAX_DURATION_MS)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    override fun onDestroy() {
        // Make sure we never leak the recorder or wakelock.
        if (isRecording) {
            isRecording = false
            recorder?.let { safeReleaseRecorder(it) }
            recorder = null
            releaseWakeLock()
        }
        super.onDestroy()
    }

    private fun isoNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())
}
