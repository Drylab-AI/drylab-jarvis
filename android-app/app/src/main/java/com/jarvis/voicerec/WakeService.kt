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

    /**
     * One listening session: its own AudioRecord and cancel flag, so a thread
     * that outlived its stop (e.g. a join timeout while the models load) can
     * never interfere with a newer session.
     */
    private class Session {
        @Volatile var cancelled = false
        @Volatile var record: AudioRecord? = null

        fun cancel() {
            cancelled = true
            // Unblock a pending blocking read so the thread exits promptly.
            runCatching { record?.stop() }
        }
    }

    // The detector is owned by the service (loading the models is the
    // expensive part) and guarded by detectorLock so onDestroy cannot close it
    // while a listener thread is mid-inference.
    private val detectorLock = Any()
    private var detector: OpenWakeWord? = null
    private var detectorClosed = false

    @Volatile private var session: Session? = null
    private var listenThread: Thread? = null

    /** Monotonic time before which the guard loop must not resume listening. */
    @Volatile private var resumeNotBefore = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        ensureChannel()

        // A microphone-type foreground service may only be started while
        // RECORD_AUDIO is actually granted (Android 14+ throws otherwise), so
        // bail out before touching startForeground if it is missing. The user
        // can flip the toggle again after granting it.
        if (!hasMicPermission()) {
            Log.w(TAG, "RECORD_AUDIO not granted; wake word cannot start")
            isRunning = false
            stopSelf()
            return START_NOT_STICKY
        }

        // Must enter the foreground promptly whenever started as an FGS.
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

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Reconcile mic ownership every [GUARD_PERIOD_MS]: listen when the
     * assistant is idle, release the mic while it runs.
     */
    private val guard = object : Runnable {
        override fun run() {
            if (!isRunning) return
            val assistantBusy = AssistantService.isRunning
            val inHandoff = SystemClock.elapsedRealtime() < resumeNotBefore
            val active = session != null
            when {
                assistantBusy && active -> stopListening()
                !assistantBusy && !inHandoff && !active -> startListening()
            }
            handler.postDelayed(this, GUARD_PERIOD_MS)
        }
    }

    private fun startGuardLoop() {
        handler.removeCallbacks(guard)
        handler.post(guard)
    }

    private fun startListening() {
        if (session != null) return
        if (!hasMicPermission()) {
            Log.w(TAG, "RECORD_AUDIO not granted; cannot listen")
            return
        }
        val s = Session()
        session = s
        listenThread = Thread({ listenLoop(s) }, "jarvis-wake").also { it.start() }
    }

    /**
     * Get (creating on first use) the shared detector, or null once the
     * service is being destroyed.
     */
    private fun detector(): OpenWakeWord? = synchronized(detectorLock) {
        if (detectorClosed) return null
        detector?.let { return it }
        return try {
            OpenWakeWord({ name -> assets.open(name).use { it.readBytes() } })
                .also { detector = it }
        } catch (e: Exception) {
            Log.e(TAG, "failed to load wake-word models", e)
            null
        }
    }

    /** Mic -> 80 ms frames -> detector, on a dedicated per-session thread. */
    @SuppressLint("MissingPermission")
    private fun listenLoop(s: Session) {
        val frame = ShortArray(OpenWakeWord.FRAME_SAMPLES)
        val minBuf = AudioRecord.getMinBufferSize(
            OpenWakeWord.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        var rec: AudioRecord? = null
        var lastWake = 0L
        var lastBeat = SystemClock.elapsedRealtime()
        var beatMax = 0f
        var beatRms = 0.0
        var beatFrames = 0
        try {
            // Loading the models can take a moment; the session may already
            // have been cancelled by then.
            if (s.cancelled) return
            val det = detector() ?: return
            if (s.cancelled) return

            rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                OpenWakeWord.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, frame.size * 2 * 4),
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized")
                return
            }
            s.record = rec
            if (s.cancelled) return

            det.reset() // never carry stale audio across listening sessions
            rec.startRecording()
            Log.i(TAG, "listening for 'Jarvis' (on-device, openWakeWord)")

            while (!s.cancelled) {
                // Fill exactly one 80 ms frame.
                var got = 0
                while (got < frame.size && !s.cancelled) {
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
                    s.cancelled = true
                    handler.post { onWake() }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "listen loop error", e)
        } finally {
            rec?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
            s.record = null
            // Only clear the shared slot if this session is still the current
            // one; a newer session must not be wiped by a stale thread.
            if (session === s) session = null
            Log.i(TAG, "stopped listening")
        }
    }

    private fun stopListening() {
        val s = session ?: return
        session = null
        s.cancel()
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
        // Mark the detector unavailable first so a thread that outlived the
        // join cannot create a new one, then close it. OpenWakeWord's methods
        // are synchronized, so close() waits for any in-flight accept().
        synchronized(detectorLock) {
            detectorClosed = true
            runCatching { detector?.close() }
            detector = null
        }
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
