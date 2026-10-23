package com.jarvis.voicerec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.LifecycleService
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback

/**
 * Always-on "Jarvis" wake-word listener (Porcupine). On detection it launches
 * [AssistantActivity]. While the assistant is running it pauses listening (one
 * mic owner at a time) and resumes when the assistant ends.
 *
 * Needs a free Picovoice access key in Prefs; without it the service posts a
 * notice and stops, and the user can still trigger the assistant with the
 * Volume-Up long-press (handled by volrecd) or the app's button.
 */
class WakeService : LifecycleService() {

    companion object {
        const val ACTION_START = "com.jarvis.voicerec.WAKE_START"
        const val ACTION_STOP = "com.jarvis.voicerec.WAKE_STOP"
        private const val TAG = "WakeService"
        private const val CHANNEL_ID = "jarvis_wake"
        private const val NOTIF_ID = 3100

        @Volatile
        var isRunning = false
            private set
    }

    private var manager: PorcupineManager? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopListening()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> start()
        }
        return START_STICKY
    }

    private fun start() {
        ensureChannel()
        startForegroundCompat()
        isRunning = true
        val key = Prefs.picovoiceKey(this)
        if (key.isBlank()) {
            Log.w(TAG, "no Picovoice key; wake word disabled")
            // Keep the FGS alive but idle so the toggle state is honoured; the
            // user can add a key later and restart. Alternatively stop.
            return
        }
        startListening(key)
    }

    private fun startListening(accessKey: String) {
        if (manager != null) return
        // Don't listen while the assistant owns the mic.
        if (AssistantService.isRunning) {
            scheduleResume()
            return
        }
        try {
            manager = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeyword(Porcupine.BuiltInKeyword.JARVIS)
                .setSensitivity(0.6f)
                .build(applicationContext, PorcupineManagerCallback { _ ->
                    onWake()
                })
            manager?.start()
            Log.i(TAG, "listening for 'Jarvis'")
        } catch (e: Exception) {
            Log.e(TAG, "porcupine init failed", e)
        }
    }

    private fun onWake() {
        Log.i(TAG, "wake word detected")
        stopListening() // release the mic for the assistant
        Vibrate.once(this)
        startActivity(
            Intent(this, AssistantActivity::class.java)
                .setAction("com.jarvis.voicerec.ASSISTANT")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        scheduleResume()
    }

    /** Poll until the assistant finishes, then resume wake listening. */
    private fun scheduleResume() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!isRunning) return
                if (AssistantService.isRunning) {
                    handler.postDelayed(this, 2000)
                } else {
                    val key = Prefs.picovoiceKey(this@WakeService)
                    if (key.isNotBlank()) startListening(key)
                }
            }
        }, 2000)
    }

    private fun stopListening() {
        try {
            manager?.stop()
            manager?.delete()
        } catch (_: Exception) {
        }
        manager = null
    }

    override fun onDestroy() {
        isRunning = false
        stopListening()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val n = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Jarvis wake word")
            .setContentText("Listening for \"Jarvis\"")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "Wake word", NotificationManager.IMPORTANCE_MIN
                )
            )
        }
    }
}
