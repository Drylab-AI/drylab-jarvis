package com.jarvis.voicerec

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink

/**
 * Back-camera live-stream service. Volume Down toggles it through
 * [CamToggleActivity].
 *
 * While streaming it:
 *  - runs CameraX on the BACK camera with an ImageAnalysis use case, encodes
 *    each frame to JPEG and pushes it to the PC as an MJPEG stream
 *    (POST /cam/video/<sid>, multipart/x-mixed-replace),
 *  - captures the mic with AudioRecord, encodes AAC and pushes it to the PC
 *    (POST /cam/audio/<sid>),
 *  - holds a partial wakelock, shows an ongoing notification, and stops itself
 *    after 2 h as a safety limit.
 *
 * The PC muxes the two streams into one mp4 and opens a live window.
 */
class CameraService : LifecycleService() {

    companion object {
        const val ACTION_TOGGLE = "com.jarvis.voicerec.CAM_TOGGLE"
        const val ACTION_START = "com.jarvis.voicerec.CAM_START"
        const val ACTION_STOP = "com.jarvis.voicerec.CAM_STOP"

        private const val TAG = "CameraService"
        private const val CHANNEL_ID = "jarvis_camera"
        private const val NOTIF_ID = 2001
        private const val MAX_DURATION_MS = 2 * 60 * 60 * 1000L

        // Stream tuning. MJPEG at 640x480/~15fps is smooth on a LAN.
        private const val TARGET_W = 640
        private const val TARGET_H = 480
        private const val JPEG_QUALITY = 60
        private const val AUDIO_SAMPLE_RATE = 44_100
        private const val AUDIO_BIT_RATE = 96_000

        @Volatile
        var isStreaming: Boolean = false
            private set
    }

    private lateinit var http: OkHttpClient
    private var cameraProvider: ProcessCameraProvider? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var wakeLock: PowerManager.WakeLock? = null
    private var startedAtMs = 0L
    private var sid: String = ""

    // Frame/audio pumps feed OkHttp streaming bodies through these.
    private var videoPump: StreamPump? = null
    private var audioPump: StreamPump? = null
    private var audioThread: Thread? = null
    private val audioStop = AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> if (!isStreaming) startStreaming()
            ACTION_STOP -> if (isStreaming) stopStreaming()
            ACTION_TOGGLE, null -> if (isStreaming) stopStreaming() else startStreaming()
        }
        return START_NOT_STICKY
    }

    private fun startStreaming() {
        ensureChannel()
        startedAtMs = SystemClock.elapsedRealtime()
        sid = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).format(Date())
        startForegroundCompat()
        isStreaming = true
        acquireWakeLock()

        http = OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(0, java.util.concurrent.TimeUnit.SECONDS) // long stream
            .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val base = Prefs.serverUrl(this)
        val device = (Build.MODEL ?: "pixel").replace(" ", "-")

        // Start the two streaming uploads. Each runs on its own dispatcher
        // thread inside OkHttp; the pumps block until frames arrive.
        videoPump = StreamPump()
        audioPump = StreamPump()
        startVideoUpload(base, device)
        startAudioUpload(base, device)

        startCamera()
        startAudioCapture()

        updateNotification()
        Log.i(TAG, "streaming started sid=$sid")
    }

    private fun stopStreaming() {
        isStreaming = false
        Log.i(TAG, "streaming stopping sid=$sid")

        // Stop camera.
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }
        cameraProvider = null

        // Stop audio.
        audioStop.set(true)
        try {
            audioThread?.join(1500)
        } catch (_: Exception) {
        }
        audioThread = null

        // End both streams (unblocks the OkHttp bodies, which then finish).
        videoPump?.close()
        audioPump?.close()

        // Tell the server this session is done so it finalizes the mp4.
        notifyServerStop()

        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- camera ------------------------------------------------------------

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider

                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(android.util.Size(TARGET_W, TARGET_H))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()

                analysis.setAnalyzer(analysisExecutor) { image ->
                    try {
                        if (isStreaming) {
                            val jpeg = imageProxyToJpeg(image)
                            if (jpeg != null) videoPump?.offerFrame(jpeg)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "frame encode failed: ${e.message}")
                    } finally {
                        image.close()
                    }
                }

                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    analysis,
                )
                Log.i(TAG, "back camera bound")
            } catch (e: Exception) {
                Log.e(TAG, "camera start failed", e)
                notifyError("Could not open back camera")
                stopStreaming()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** YUV_420_888 ImageProxy -> NV21 -> JPEG bytes. */
    private fun imageProxyToJpeg(image: ImageProxy): ByteArray? {
        val nv21 = yuv420ToNv21(image) ?: return null
        val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, image.width, image.height), JPEG_QUALITY, out)
        return out.toByteArray()
    }

    private fun yuv420ToNv21(image: ImageProxy): ByteArray? {
        if (image.format != ImageFormat.YUV_420_888) return null
        val w = image.width
        val h = image.height
        val ySize = w * h
        val nv21 = ByteArray(ySize + 2 * (w / 2) * (h / 2))

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        // Copy Y row by row honouring rowStride.
        var pos = 0
        val yBuf = yPlane.buffer
        val yRowStride = yPlane.rowStride
        for (row in 0 until h) {
            val start = row * yRowStride
            yBuf.position(start)
            yBuf.get(nv21, pos, w)
            pos += w
        }

        // Interleave V,U for NV21.
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val uvRowStride = uPlane.rowStride
        val uvPixStride = uPlane.pixelStride
        val chromaH = h / 2
        val chromaW = w / 2
        for (row in 0 until chromaH) {
            var uCol = row * uvRowStride
            var vCol = row * uvRowStride
            for (col in 0 until chromaW) {
                nv21[pos++] = vBuf.get(vCol)
                nv21[pos++] = uBuf.get(uCol)
                uCol += uvPixStride
                vCol += uvPixStride
            }
        }
        return nv21
    }

    // ---- audio -------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun startAudioCapture() {
        audioStop.set(false)
        audioThread = Thread {
            var record: AudioRecord? = null
            var codec: MediaCodec? = null
            try {
                val minBuf = AudioRecord.getMinBufferSize(
                    AUDIO_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                val bufSize = maxOf(minBuf, 4096)
                record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    AUDIO_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize,
                )

                val fmt = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC,
                    AUDIO_SAMPLE_RATE,
                    1,
                ).apply {
                    setInteger(
                        MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufSize)
                }
                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                record.startRecording()

                val info = MediaCodec.BufferInfo()
                val pcm = ByteArray(bufSize)
                while (!audioStop.get()) {
                    val read = record.read(pcm, 0, pcm.size)
                    if (read > 0) {
                        val inIdx = codec.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val ib = codec.getInputBuffer(inIdx)!!
                            ib.clear()
                            ib.put(pcm, 0, read)
                            codec.queueInputBuffer(inIdx, 0, read, System.nanoTime() / 1000, 0)
                        }
                    }
                    var outIdx = codec.dequeueOutputBuffer(info, 0)
                    while (outIdx >= 0) {
                        val ob = codec.getOutputBuffer(outIdx)!!
                        // Skip the codec-config (CSD/ESDS) buffer: it is not an
                        // audio frame and wrapping it in ADTS corrupts the
                        // stream (ffmpeg: "Input buffer exhausted").
                        val isConfig =
                            (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (!isConfig && info.size > 0) {
                            val adts = ByteArray(7 + info.size)
                            addAdtsHeader(adts, info.size)
                            ob.position(info.offset)
                            ob.get(adts, 7, info.size)
                            audioPump?.offerFrame(adts)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        outIdx = codec.dequeueOutputBuffer(info, 0)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "audio capture failed", e)
            } finally {
                try {
                    record?.stop()
                } catch (_: Exception) {
                }
                try {
                    record?.release()
                } catch (_: Exception) {
                }
                try {
                    codec?.stop()
                } catch (_: Exception) {
                }
                try {
                    codec?.release()
                } catch (_: Exception) {
                }
            }
        }.also { it.start() }
    }

    /** Write a 7-byte ADTS header for an AAC-LC frame of payloadLen bytes. */
    private fun addAdtsHeader(packet: ByteArray, payloadLen: Int) {
        val profile = 2 // AAC LC
        val freqIdx = 4 // 44100 Hz
        val chanCfg = 1 // mono
        val frameLen = payloadLen + 7
        packet[0] = 0xFF.toByte()
        packet[1] = 0xF9.toByte()
        packet[2] = (((profile - 1) shl 6) + (freqIdx shl 2) + (chanCfg shr 2)).toByte()
        packet[3] = (((chanCfg and 3) shl 6) + (frameLen shr 11)).toByte()
        packet[4] = ((frameLen and 0x7FF) shr 3).toByte()
        packet[5] = (((frameLen and 7) shl 5) + 0x1F).toByte()
        packet[6] = 0xFC.toByte()
    }

    // ---- streaming uploads -------------------------------------------------

    private fun startVideoUpload(base: String, device: String) {
        val boundary = "jarvisframe"
        val body = object : RequestBody() {
            override fun contentType() =
                "multipart/x-mixed-replace; boundary=$boundary".toMediaType()

            override fun writeTo(sink: BufferedSink) {
                val pump = videoPump ?: return
                while (true) {
                    val frame = pump.take() ?: break
                    sink.writeUtf8("--$boundary\r\n")
                    sink.writeUtf8("Content-Type: image/jpeg\r\n")
                    sink.writeUtf8("Content-Length: ${frame.size}\r\n\r\n")
                    sink.write(frame)
                    sink.writeUtf8("\r\n")
                    sink.flush()
                }
                sink.writeUtf8("--$boundary--\r\n")
            }
        }
        val req = Request.Builder()
            .url("$base/cam/video/$sid")
            .header("X-Device", device)
            .header("X-Started-At", sid)
            .post(body)
            .build()
        http.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                Log.w(TAG, "video stream ended: ${e.message}")
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.close()
                Log.i(TAG, "video stream closed: ${response.code}")
            }
        })
    }

    private fun startAudioUpload(base: String, device: String) {
        val body = object : RequestBody() {
            override fun contentType() = "audio/aac".toMediaType()

            override fun writeTo(sink: BufferedSink) {
                val pump = audioPump ?: return
                while (true) {
                    val frame = pump.take() ?: break
                    sink.write(frame)
                    sink.flush()
                }
            }
        }
        val req = Request.Builder()
            .url("$base/cam/audio/$sid")
            .header("X-Device", device)
            .header("X-Started-At", sid)
            .post(body)
            .build()
        http.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                Log.w(TAG, "audio stream ended: ${e.message}")
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.close()
                Log.i(TAG, "audio stream closed: ${response.code}")
            }
        })
    }

    private fun notifyServerStop() {
        try {
            val base = Prefs.serverUrl(this)
            val req = Request.Builder()
                .url("$base/cam/stop/$sid")
                .post(ByteArray(0).toRequestBody(null))
                .build()
            // Fire and forget on a short-lived client.
            OkHttpClient().newCall(req).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {}
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    response.close()
                }
            })
        } catch (_: Exception) {
        }
    }

    // ---- notification / lifecycle -----------------------------------------

    private fun startForegroundCompat() {
        val n = buildNotification()
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, type)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, CamToggleActivity::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Streaming back camera")
            .setContentText("Press Volume Down again to stop")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .build()
    }

    private fun notifyError(msg: String) {
        ensureChannel()
        val n = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Jarvis camera")
            .setContentText(msg)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID + 1, n)
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Camera stream",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:cam").apply {
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
        if (isStreaming) {
            isStreaming = false
            try {
                cameraProvider?.unbindAll()
            } catch (_: Exception) {
            }
            audioStop.set(true)
            videoPump?.close()
            audioPump?.close()
            releaseWakeLock()
        }
        analysisExecutor.shutdown()
        super.onDestroy()
    }
}
