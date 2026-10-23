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
        private const val CAM_W = 768
        private const val CAM_H = 768
        private const val CAM_JPEG_QUALITY = 70

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

    private var audioTrack: AudioTrack? = null
    @Volatile private var playbackGen = 0  // bumped on flush to drop stale audio

    private var cameraProvider: ProcessCameraProvider? = null
    private val camExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var cameraOn = false
    @Volatile private var lastFrameMs = 0L

    private var wakeLock: PowerManager.WakeLock? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

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
        initAudioTrack()
        connect()
        startMic()
        emit("""{"type":"state","state":"connecting"}""")
    }

    // ---- websocket ---------------------------------------------------------

    private fun connect() {
        val base = Prefs.serverUrl(this)
        val wsUrl = base.replaceFirst("http://", "ws://")
            .replaceFirst("https://", "wss://") + "/assistant/ws"
        val client = OkHttpClient.Builder()
            .pingInterval(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        http = client
        val req = Request.Builder().url(wsUrl).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "ws open")
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
                emit("""{"type":"state","state":"error"}""")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "ws closed $code $reason")
            }
        })
    }

    private fun handleControl(text: String) {
        // Forward everything to the activity for display.
        emit(text)
        try {
            val o = JSONObject(text)
            when (o.optString("type")) {
                "flush" -> flushPlayback()
                "tool" -> onTool(o.optString("action"))
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

    // ---- playback (24 kHz PCM down) ----------------------------------------

    private fun initAudioTrack() {
        val minBuf = AudioTrack.getMinBufferSize(
            OUT_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(OUT_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, OUT_RATE)) // ~0.5s headroom
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        audioTrack = track
    }

    private fun playPcm(pcm: ByteArray) {
        val track = audioTrack ?: return
        val gen = playbackGen
        // Blocking write on the WS reader thread is fine (OkHttp dispatches
        // messages serially); if a flush bumped the generation, skip.
        if (gen != playbackGen) return
        try {
            track.write(pcm, 0, pcm.size)
        } catch (_: Exception) {
        }
    }

    private fun flushPlayback() {
        playbackGen++
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.play()
        } catch (_: Exception) {
        }
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
        cameraOn = true
        promoteToCamera()
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                Log.i(TAG, "binding back camera for the assistant")
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(CAM_W, CAM_H))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                analysis.setAnalyzer(camExecutor) { image ->
                    try {
                        val now = System.currentTimeMillis()
                        if (cameraOn && now - lastFrameMs >= 1000) {
                            lastFrameMs = now
                            val jpeg = CameraJpeg.toJpeg(image, CAM_JPEG_QUALITY)
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
                emit("""{"type":"camera","on":true}""")
            } catch (e: Exception) {
                Log.e(TAG, "camera start failed", e)
                cameraOn = false
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        cameraOn = false
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        cameraProvider = null
        demoteFromCamera()
        emit("""{"type":"camera","on":false}""")
    }

    // ---- lifecycle / notification ------------------------------------------

    private fun stopEverything() {
        isRunning = false
        micStop.set(true)
        try { micThread?.join(800) } catch (_: Exception) {}
        micThread = null
        stopCamera()
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        ws = null
        try {
            audioTrack?.pause(); audioTrack?.flush(); audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
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
