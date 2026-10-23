package com.jarvis.voicerec

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import kotlin.math.sqrt

/**
 * Always-on "Jarvis" wake-word listener, fully on-device and free: it runs the
 * openWakeWord models through ONNX Runtime ([OpenWakeWord]). No account, no
 * key, no expiry, and audio never leaves the phone. On detection it launches
 * [AssistantActivity].
 *
 * One mic owner at a time: a guard loop pauses detection whenever
 * [AssistantService] is running (whatever started it: this wake word, the
 * Volume-Up long-press, or the in-app button) and resumes when it ends.
 *
 * Runs only while the user's wake toggle ([Prefs.wakeEnabled]) is on.
 */
class WakeService : LifecycleService() {

    companion object {
        const val ACTION_START = "com.jarvis.voicerec.WAKE_START"
        const val ACTION_STOP = "com.jarvis.voicerec.WAKE_STOP"
        private const val TAG = "WakeService"
        private const val CHANNEL_ID = "jarvis_wake"
        private const val NOTIF_ID = 3100

        /** Score at/above which a frame counts as "Jarvis" (tunable 0.3-0.6). */
        private const val THRESHOLD = 0.5f
        /** Ignore re-triggers for this long after a detection. */
        private const val DEBOUNCE_MS = 2_000L
        /** After a wake, keep the mic free this long so the assistant can grab it. */
        private const val HANDOFF_MS = 5_000L
        private const val GUARD_PERIOD_MS = 1_500L
        /** Log a heartbeat (signal level + best score) this often, for tuning. */
        private const val HEARTBEAT_MS = 10_000L

        @Volatile
        var isRunning = false
            private set
    }

    private val handler = Handler(Looper.getMainLooper())

    // Owned by the listener thread; the detector outlives individual listening
    // sessions (loading the models once is the expensive part).
    @Volatile private var detector: OpenWakeWord? = null
    @Volatile private var listening = false
    @Volatile private var record: AudioRecord? = null
    private var listenThread: Thread? = null

    /** Monotonic time before which the guard loop must not resume listening. */
    @Volatile private var resumeNotBefore = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Must enter the foreground promptly whenever started as an FGS.
        ensureChannel()
        startForegroundCompat()

        if (intent?.action == ACTION_STOP || !Prefs.wakeEnabled(this)) {
            Log.i(TAG, "wake word off; stopping")
            shutdown()
            return START_NOT_STICKY
        }
        if (!isRunning) {
            isRunning = true
            startGuardLoop()
        }
        return START_STICKY
    }

    /**
     * Reconcile mic ownership every [GUARD_PERIOD_MS]: listen when the
     * assistant is idle, release the mic while it runs.
     */
    private val guard = object : Runnable {
        override fun run() {
            if (!isRunning) return
            val assistantBusy = AssistantService.isRunning
            val inHandoff = SystemClock.elapsedRealtime() < resumeNotBefore
            when {
                assistantBusy && listening -> stopListening()
                !assistantBusy && !inHandoff && !listening -> startListening()
            }
            handler.postDelayed(this, GUARD_PERIOD_MS)
        }
    }

    private fun startGuardLoop() {
        handler.removeCallbacks(guard)
        handler.post(guard)
    }

    @SuppressLint("MissingPermission")
    private fun startListening() {
        if (listening) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "RECORD_AUDIO not granted; cannot listen")
            return
        }
        listening = true
        listenThread = Thread({ listenLoop() }, "jarvis-wake").also { it.start() }
    }

    /** Mic -> 80 ms frames -> detector, on a dedicated thread. */
    @SuppressLint("MissingPermission")
    private fun listenLoop() {
        val det = detector ?: try {
            OpenWakeWord({ name -> assets.open(name).use { it.readBytes() } })
                .also { detector = it }
        } catch (e: Exception) {
            Log.e(TAG, "failed to load wake-word models", e)
            listening = false
            return
        }
        det.reset() // never carry stale audio across listening sessions

        val frame = ShortArray(OpenWakeWord.FRAME_SAMPLES)
        val minBuf = AudioRecord.getMinBufferSize(
            OpenWakeWord.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                OpenWakeWord.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, frame.size * 2 * 4),
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord init failed", e)
            listening = false
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized")
            rec.release()
            listening = false
            return
        }
        record = rec

        var lastWake = 0L
        var lastBeat = SystemClock.elapsedRealtime()
        var beatMax = 0f
        var beatRms = 0.0
        var beatFrames = 0
        try {
            rec.startRecording()
            Log.i(TAG, "listening for 'Jarvis' (on-device, openWakeWord)")
            while (listening) {
                // Fill exactly one 80 ms frame.
                var got = 0
                while (got < frame.size && listening) {
                    val n = rec.read(frame, got, frame.size - got, AudioRecord.READ_BLOCKING)
                    if (n <= 0) break
                    got += n
                }
                if (got < frame.size) break

                val score = det.accept(frame)
                val now = SystemClock.elapsedRealtime()

                beatMax = maxOf(beatMax, score)
                beatRms += rms(frame)
                beatFrames++
                if (now - lastBeat >= HEARTBEAT_MS) {
                    Log.i(TAG, "alive rms=%.0f maxScore=%.3f".format(beatRms / beatFrames, beatMax))
                    lastBeat = now; beatMax = 0f; beatRms = 0.0; beatFrames = 0
                }

                if (score >= THRESHOLD && now - lastWake >= DEBOUNCE_MS) {
                    lastWake = now
                    Log.i(TAG, "wake word detected (score=%.3f)".format(score))
                    // Free the mic for the assistant and keep it free briefly
                    // so the guard loop doesn't grab it back before the
                    // assistant's own service has started.
                    resumeNotBefore = now + HANDOFF_MS
                    listening = false
                    handler.post { onWake() }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "listen loop error", e)
        } finally {
            runCatching { rec.stop() }
            rec.release()
            record = null
            listening = false
            Log.i(TAG, "stopped listening")
        }
    }

    private fun stopListening() {
        listening = false
        // Unblock a pending read so the thread exits promptly.
        runCatching { record?.stop() }
        listenThread?.let { t ->
            if (t !== Thread.currentThread()) runCatching { t.join(1_500) }
        }
        listenThread = null
    }

    private fun onWake() {
        if (!isRunning) return
        Vibrate.once(this)
        try {
            startActivity(
                Intent(this, AssistantActivity::class.java)
                    .setAction("com.jarvis.voicerec.ASSISTANT")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.e(TAG, "could not open the assistant", e)
        }
        // The guard loop resumes listening once the assistant ends.
    }

    private fun rms(frame: ShortArray): Double {
        var sum = 0.0
        for (s in frame) sum += s.toDouble() * s
        return sqrt(sum / frame.size)
    }

    private fun shutdown() {
        isRunning = false
        handler.removeCallbacks(guard)
        stopListening()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacks(guard)
        stopListening()
        runCatching { detector?.close() }
        detector = null
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val n = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Jarvis wake word")
            .setContentText("Listening for \"Jarvis\" on this phone")
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
                NotificationChannel(CHANNEL_ID, "Wake word", NotificationManager.IMPORTANCE_MIN)
            )
        }
    }
}
