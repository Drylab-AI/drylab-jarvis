package com.jarvis.voicerec

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.PowerManager
import android.util.Size
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The phone side of the Jarvis voice assistant. A foreground service
 * (microphone + camera) that:
 *  - opens a WebSocket to the PC's /assistant/ws,
 *  - captures mic audio as 16 kHz mono PCM and streams it up (opcode 0x01),
 *  - plays the assistant's 24 kHz mono PCM replies via AudioTrack (opcode 0x11),
 *  - flushes playback instantly on a barge-in ("flush") control message,
 *  - turns the back camera on/off on tool commands and streams JPEG frames
 *    (opcode 0x02) at ~1 fps while on,
 *  - relays state / transcript / show_image / tool events to AssistantActivity
 *    via local broadcasts.
 */
class AssistantService : LifecycleService() {

    companion object {
        const val ACTION_START = "com.jarvis.voicerec.ASSIST_START"
        const val ACTION_STOP = "com.jarvis.voicerec.ASSIST_STOP"

        // Local broadcast out to the activity.
        const val BROADCAST = "com.jarvis.voicerec.ASSIST_EVENT"
        const val EXTRA_JSON = "json"

        private const val TAG = "AssistantService"
        private const val CHANNEL_ID = "jarvis_assistant"
        private const val NOTIF_ID = 3001

        private const val IN_RATE = 16_000
        private const val OUT_RATE = 24_000
        // 640x480 @ ~10 fps, q60 (~40 KB/frame, ~400 KB/s on the LAN): smooth
        // in the PC live window. The server forwards only the newest frame,
        // at most 1 fps, to Gemini, so this doesn't grow the model's context.
        private const val CAM_W = 640
        private const val CAM_H = 480
        private const val CAM_JPEG_QUALITY = 60
        private const val CAM_FRAME_MS = 100L

        /** True while the assistant holds the back camera. */
        @Volatile
        var cameraBusy: Boolean = false
            private set

        private const val OP_MIC_PCM: Byte = 0x01
        private const val OP_CAM_JPEG: Byte = 0x02
        private const val OP_ASSISTANT_PCM: Byte = 0x11

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private var ws: WebSocket? = null
    private var http: OkHttpClient? = null

    private var micThread: Thread? = null
    private val micStop = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null

    @Volatile private var player: Player? = null
    @Volatile private var turnTaker: LocalTurnTaker? = null
    private var fillers: List<ByteArray> = emptyList()

    private var cameraProvider: ProcessCameraProvider? = null
    private val camExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var cameraOn = false
    @Volatile private var lastFrameMs = 0L
    private var encodedFrames = 0L

    private var wakeLock: PowerManager.WakeLock? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // ---- test mode (debug builds only, see TestMode) -----------------------
    private var testMode = false
    private val scriptedMic = ScriptedMic()
    private var injectReceiver: android.content.BroadcastReceiver? = null
    private var playedOut: java.io.FileOutputStream? = null
    /** Phone clock time the last reply chunk was handed to the AudioTrack. */
    @Volatile private var lastPcmAt = 0L
    /** Last state sent by the server, so the timeline logs only changes. */
    @Volatile private var lastState = ""

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }
            else -> if (!isRunning) start()
        }
        return START_NOT_STICKY
    }

    private fun start() {
        ensureChannel()
        startForegroundCompat()
        isRunning = true
        acquireWakeLock()
        startTestMode()
        initAudioTrack()
        connect()
        startMic()
        Timeline.mark("assist_start", "test=$testMode")
        emit("""{"type":"state","state":"connecting"}""")
    }

    /**
     * Debug-only: listen for clip injections from the harness and record what
     * is actually played, so a test can check timing from the real output.
     */
    private fun startTestMode() {
        testMode = TestMode.enabled(this)
        if (!testMode) return
        val dir = TestMode.dir(this).apply { mkdirs() }
        playedOut = runCatching {
            java.io.FileOutputStream(java.io.File(dir, "played.pcm"), false)
        }.getOrNull()
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                i ?: return
                if (i.getBooleanExtra("clear", false)) {
                    scriptedMic.clear()
                    return
                }
                val path = i.getStringExtra("path") ?: return
                scriptedMic.enqueue(
                    path,
                    i.getStringExtra("name") ?: java.io.File(path).nameWithoutExtension,
                    i.getStringExtra("trigger") ?: "now",
                    i.getLongExtra("delay", 0L),
                )
            }
        }
        injectReceiver = r
        // Exported only in test mode: `am broadcast` from adb runs as the
        // shell user, outside the app.
        registerReceiver(r, android.content.IntentFilter(TestMode.ACTION_INJECT), RECEIVER_EXPORTED)
        Log.i(TAG, "TEST MODE on (realMic=${TestMode.realMic(this)})")
    }

    private fun stopTestMode() {
        injectReceiver?.let { runCatching { unregisterReceiver(it) } }
        injectReceiver = null
        runCatching { playedOut?.close() }
        playedOut = null
    }

    // ---- websocket ---------------------------------------------------------

    /** Consecutive failed connects, for the reconnect backoff. */
    private var reconnectAttempts = 0

    private fun connect() {
        val base = Prefs.serverUrl(this)
        val wsUrl = base.replaceFirst("http://", "ws://")
            .replaceFirst("https://", "wss://") + "/assistant/ws"
        val client = http ?: OkHttpClient.Builder()
            // 5 s pings: a dead PC (restart, Wi-Fi drop) is noticed within
            // ~10 s instead of the 40 s a 20 s interval took.
            .pingInterval(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        http = client
        val req = Request.Builder().url(wsUrl).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "ws open")
                Timeline.mark("ws_open")
                reconnectAttempts = 0
                // A reconnect after a server restart: the camera may still be
                // on, but the new server session knows nothing about it.
                if (cameraOn) mainHandler.post { stopCamera() }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleControl(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (bytes.size < 1) return
                when (bytes[0]) {
                    OP_ASSISTANT_PCM -> playPcm(bytes.substring(1).toByteArray())
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "ws failure: ${t.message}")
                emit("""{"type":"state","state":"connecting"}""")
                scheduleReconnect(webSocket, "failure")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "ws closed $code $reason")
                scheduleReconnect(webSocket, "closed_$code")
            }
        })
    }

    /**
     * The conversation outlives one socket: the PC server may restart or
     * Wi-Fi may blip. Before, the phone sat on a dead socket until the user
     * ended the chat. Reconnect with backoff (0.5 s, 1 s, 2 s ... max 8 s)
     * while the assistant is still running.
     */
    private fun scheduleReconnect(dead: WebSocket, why: String) {
        if (!isRunning || dead !== ws) return
        ws = null
        player?.stop()
        val delay = minOf(500L shl minOf(reconnectAttempts, 4), 8_000L)
        reconnectAttempts++
        Timeline.mark("ws_reconnect", "why=$why in_ms=$delay")
        mainHandler.postDelayed({ if (isRunning && ws == null) connect() }, delay)
    }

    private fun handleControl(text: String) {
        // Forward everything to the activity for display.
        emit(text)
        try {
            val o = JSONObject(text)
            when (o.optString("type")) {
                "flush" -> {
                    Timeline.mark("server_flush")
                    flushPlayback()
                    // Gemini confirmed the interruption: what it sends next
                    // is the new answer.
                    player?.unmute()
                }
                "tool" -> {
                    Timeline.mark("tool", "action=${o.optString("action")}")
                    onTool(o.optString("action"))
                }
                "state" -> {
                    val s = o.optString("state")
                    if (s != lastState) {
                        lastState = s
                        Timeline.mark("state", s)
                    }
                    // The interrupted reply's turn is over on the server.
                    if (s == "listening") player?.unmute()
                }
                "show_image" -> Timeline.mark("show_image")
            }
        } catch (_: Exception) {
        }
    }

    private fun onTool(action: String) {
        Log.i(TAG, "tool from server: $action")
        // Control messages arrive on the OkHttp reader thread, but CameraX
        // binding and startForeground must run on the main thread.
        mainHandler.post { onToolMain(action) }
    }

    private fun onToolMain(action: String) {
        when (action) {
            "open_camera" -> startCamera()
            "close_camera" -> stopCamera()
            "start_recording" -> startForegroundService(
                Intent(this, RecordService::class.java)
                    .setAction(RecordService.ACTION_START)
            )
            "stop_recording" -> startForegroundService(
                Intent(this, RecordService::class.java)
                    .setAction(RecordService.ACTION_STOP)
            )
        }
    }

    // ---- mic capture (16 kHz PCM up) ---------------------------------------

    @SuppressLint("MissingPermission")
    private fun startMic() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            emit("""{"type":"error","message":"microphone permission not granted"}""")
            return
        }
        micStop.set(false)
        if (testMode && !TestMode.realMic(this)) {
            micThread = Thread({ scriptedMicLoop() }, "jarvis-mic-test").also { it.start() }
            return
        }
        micThread = Thread {
            val minBuf = AudioRecord.getMinBufferSize(
                IN_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val bufSize = maxOf(minBuf, IN_RATE / 10 * 2) // ~100ms
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                IN_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize,
            )
            audioRecord = rec
            // ~40 ms chunks for low latency.
            val chunk = ByteArray(IN_RATE / 25 * 2)
            try {
                rec.startRecording()
                while (!micStop.get()) {
                    val n = rec.read(chunk, 0, chunk.size)
                    if (n > 0) {
                        val shorts = ShortArray(n / 2)
                        ByteBuffer.wrap(chunk, 0, n).order(ByteOrder.LITTLE_ENDIAN)
                            .asShortBuffer().get(shorts)
                        onMicFrame(shorts)
                        val frame = ByteArray(1 + n)
                        frame[0] = OP_MIC_PCM
                        System.arraycopy(chunk, 0, frame, 1, n)
                        ws?.send(frame.toByteString())
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "mic error", e)
            } finally {
                try { rec.stop() } catch (_: Exception) {}
                try { rec.release() } catch (_: Exception) {}
            }
        }.also { it.start() }
    }

    /**
     * Test-mode mic: 40 ms frames from [ScriptedMic], paced against the clock
     * (not sleep-per-frame, which drifts) so it is real time like a mic.
     */
    private fun scriptedMicLoop() {
        val frameSamples = IN_RATE / 25
        val samples = ShortArray(frameSamples)
        val start = Timeline.now()
        var sent = 0L
        while (!micStop.get()) {
            scriptedMic.next(samples)
            val frame = ByteArray(1 + frameSamples * 2)
            frame[0] = OP_MIC_PCM
            ByteBuffer.wrap(frame, 1, frameSamples * 2)
                .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)
            onMicFrame(samples)
            ws?.send(frame.toByteString())
            sent++
            val due = start + sent * 40
            val wait = due - Timeline.now()
            if (wait > 0) Thread.sleep(wait)
        }
    }

    /** Every 40 ms mic frame (real or scripted) feeds the local layer. */
    private fun onMicFrame(samples: ShortArray) {
        try {
            turnTaker?.accept(samples)
        } catch (e: Exception) {
            Log.w(TAG, "turn-taker error: ${e.message}")
        }
    }

    // ---- playback (24 kHz PCM down) ----------------------------------------

    private fun initAudioTrack() {
        val p = Player(OUT_RATE)
        p.tap = playedOut
        p.listener = object : Player.Listener {
            override fun onReplyStart(source: String) {
                val now = Timeline.now()
                // A reply starts when sound begins after a quiet gap; 600 ms
                // is longer than any gap inside one streamed reply.
                if (source == "filler") {
                    Timeline.mark("filler_start")
                } else if (now - lastPcmAt > 600) {
                    Timeline.mark("reply_audio_start", "source=model")
                    scriptedMic.onAssistantSpeaking(now)
                }
                if (source == "model") lastPcmAt = now
            }

            override fun onIdle() {}
        }
        p.start()
        player = p
        loadInteractionLayer()
    }

    /** Silero VAD + bundled fillers. Optional: the assistant works without. */
    private fun loadInteractionLayer() {
        fillers = runCatching {
            assets.list("fillers")!!.filter { it.endsWith(".pcm") }.sorted()
                .map { name -> assets.open("fillers/$name").use { it.readBytes() } }
        }.getOrDefault(emptyList())
        turnTaker = runCatching {
            LocalTurnTaker(
                assets.open("vad/silero_vad.onnx").use { it.readBytes() },
                object : LocalTurnTaker.Actions {
                    override fun assistantSpeaking(): Boolean = player?.playing != null
                    override fun lastReplyAt(): Long = lastPcmAt
                    override fun cutIn() {
                        player?.stop()
                        player?.muteReplyUntilServerAck()
                    }
                    override fun playFiller(): Boolean = playRandomFiller()
                },
            )
        }.onFailure { Log.w(TAG, "local turn-taker unavailable: ${it.message}") }.getOrNull()
        Log.i(TAG, "interaction layer: vad=${turnTaker != null} fillers=${fillers.size}")
    }

    private var lastFiller = -1

    private fun playRandomFiller(): Boolean {
        if (fillers.isEmpty()) return false
        // Never the same clip twice in a row: repetition is what makes
        // canned fillers sound robotic.
        var i = (fillers.indices).random()
        if (fillers.size > 1 && i == lastFiller) i = (i + 1) % fillers.size
        lastFiller = i
        return player?.playFiller(fillers[i]) == true
    }

    private fun playPcm(pcm: ByteArray) {
        player?.enqueueModel(pcm)
    }

    private fun flushPlayback() {
        player?.stop()
    }

    // ---- camera on demand (JPEG up ~1 fps) ---------------------------------

    private fun startCamera() {
        if (cameraOn) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            emit("""{"type":"error","message":"camera permission not granted"}""")
            return
        }
        if (CameraService.isStreaming) {
            // Both share one CameraProvider and each unbinds the other's use
            // cases, so the second one silently killed the first. Refuse
            // instead of breaking the Volume-Down stream.
            Timeline.mark("cam_refused", "by=volume_down_stream")
            emit("""{"type":"error","message":"camera busy: the Volume-Down stream is on"}""")
            return
        }
        cameraOn = true
        cameraBusy = true
        promoteToCamera()
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                Log.i(TAG, "binding back camera for the assistant")
                val analysis = ImageAnalysis.Builder()
                    // The HAL picked 1080x1080 for the old 768x768 target;
                    // 640x480 is 4x fewer pixels to convert and encode.
                    .setTargetResolution(Size(CAM_W, CAM_H))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                analysis.setAnalyzer(camExecutor) { image ->
                    try {
                        val now = System.currentTimeMillis()
                        // Schedule against a fixed 100 ms grid instead of
                        // "100 ms since the last frame": the analyser only
                        // ticks every ~33 ms, so "since last" always waited
                        // for the 4th tick and settled at 7.5 fps (measured).
                        if (cameraOn && now >= lastFrameMs) {
                            lastFrameMs = maxOf(lastFrameMs + CAM_FRAME_MS, now - CAM_FRAME_MS)
                            val t0 = System.nanoTime()
                            val jpeg = CameraJpeg.toJpeg(image, CAM_JPEG_QUALITY)
                            if (++encodedFrames % 50 == 1L) {
                                Timeline.mark("cam_encode", "ms=${(System.nanoTime() - t0) / 1_000_000} " +
                                    "size=${image.width}x${image.height} bytes=${jpeg?.size}")
                            }
                            if (jpeg != null) {
                                val frame = ByteArray(1 + jpeg.size)
                                frame[0] = OP_CAM_JPEG
                                System.arraycopy(jpeg, 0, frame, 1, jpeg.size)
                                ws?.send(frame.toByteString())
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "frame err: ${e.message}")
                    } finally {
                        image.close()
                    }
                }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                Timeline.mark("camera_bound")
                emit("""{"type":"camera","on":true}""")
            } catch (e: Exception) {
                Log.e(TAG, "camera start failed", e)
                cameraOn = false
                cameraBusy = false
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        val wasOn = cameraOn
        cameraOn = false
        cameraBusy = false
        // Only unbind what we bound: unbindAll() on a camera we never opened
        // would kill a running Volume-Down stream (shared CameraProvider).
        if (wasOn) try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        cameraProvider = null
        demoteFromCamera()
        Timeline.mark("camera_off")
        emit("""{"type":"camera","on":false}""")
    }

    // ---- lifecycle / notification ------------------------------------------

    private fun stopEverything() {
        isRunning = false
        micStop.set(true)
        try { micThread?.join(800) } catch (_: Exception) {}
        micThread = null
        stopCamera()
        stopTestMode()
        Timeline.mark("assist_stop")
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        ws = null
        player?.release()
        player = null
        turnTaker?.close()
        turnTaker = null
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (isRunning) stopEverything()
        camExecutor.shutdown()
        super.onDestroy()
    }

    private fun emit(json: String) {
        sendBroadcast(
            Intent(BROADCAST)
                .setPackage(packageName)
                .putExtra(EXTRA_JSON, json)
        )
    }

    private fun startForegroundCompat() {
        // Start as MICROPHONE-only. Camera type is added later (promoteToCamera)
        // only when the camera is actually turned on, and only if CAMERA is
        // granted - starting a camera-typed FGS without the permission throws.
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun promoteToCamera() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            startForeground(
                NOTIF_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            )
        } catch (e: Exception) {
            Log.w(TAG, "could not promote FGS to camera: ${e.message}")
        }
    }

    private fun demoteFromCamera() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            startForeground(
                NOTIF_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } catch (_: Exception) {
        }
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Jarvis is listening")
            .setContentText("Tap the app to end the conversation")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Assistant",
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:assist").apply {
            setReferenceCounted(false)
            acquire(60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wakeLock = null
    }
}
