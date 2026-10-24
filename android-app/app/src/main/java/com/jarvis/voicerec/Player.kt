package com.jarvis.voicerec

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The assistant's speaker: 24 kHz mono PCM on its own thread.
 *
 * Before, replies were written to the AudioTrack straight from the OkHttp
 * reader thread with a blocking write. That thread was then stuck behind up
 * to 0.5 s of buffered audio, so a "stop talking" message from the server
 * (or the user cutting in) waited in line behind the audio it was meant to
 * cancel, and the backpressure even stalled the server's send loop.
 *
 * Now the network thread only enqueues. [stop] drops everything queued and
 * flushes the track at once, from any thread.
 *
 * Two sources share the track: `model` (the reply) and `filler` (a short
 * on-phone "hmm" while the reply is on its way). Model audio arriving cuts
 * the filler, because the real answer always wins.
 */
class Player(private val rate: Int = 24_000) {

    interface Listener {
        /** First chunk of a reply actually reached the speaker. */
        fun onReplyStart(source: String)
        /** The queue ran dry after playing (reply finished or was cut). */
        fun onIdle()
    }

    private class Chunk(val gen: Int, val source: String, val pcm: ByteArray)

    private val q = LinkedBlockingQueue<Chunk>()
    private val gen = AtomicInteger(0)
    @Volatile private var running = true
    @Volatile var listener: Listener? = null
    /** Test mode: a copy of exactly what was written to the speaker. */
    @Volatile var tap: OutputStream? = null

    /** Source of the chunk being played now, or null when silent. */
    @Volatile var playing: String? = null
        private set
    /** Phone clock of the last chunk written to the track. */
    @Volatile var lastWriteAt = 0L
        private set

    private val track: AudioTrack = run {
        val minBuf = AudioTrack.getMinBufferSize(
            rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // VOICE_COMMUNICATION, not MEDIA: this routes the reply
                    // through the same path as the VOICE_COMMUNICATION mic,
                    // so the phone's echo canceller removes Jarvis's own voice
                    // from what the mic hears. The on-phone cut-in detector
                    // depends on that, or Jarvis would interrupt itself.
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            // ~120 ms: enough to ride out network jitter, small enough that
            // a stop is heard almost at once.
            .setBufferSizeInBytes(maxOf(minBuf, rate / 8 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private val thread = Thread({ loop() }, "jarvis-player").apply {
        priority = Thread.MAX_PRIORITY
    }

    fun start() {
        track.play()
        thread.start()
    }

    /** Queue reply audio (any thread). Cuts a filler that is playing. */
    fun enqueueModel(pcm: ByteArray) {
        if (playing == "filler" || q.peek()?.source == "filler") cutFiller()
        q.offer(Chunk(gen.get(), "model", pcm))
    }

    /** Queue a filler clip, unless anything is already playing or queued. */
    fun playFiller(pcm: ByteArray): Boolean {
        if (playing != null || q.isNotEmpty()) return false
        // Small chunks so a cut takes effect within ~20 ms.
        val step = rate / 50 * 2
        val g = gen.get()
        var i = 0
        while (i < pcm.size) {
            val n = minOf(step, pcm.size - i)
            q.offer(Chunk(g, "filler", pcm.copyOfRange(i, i + n)))
            i += n
        }
        return true
    }

    /** Drop all queued and buffered audio now (cut-in, server flush). */
    fun stop() {
        gen.incrementAndGet()
        q.clear()
        try {
            track.pause()
            track.flush()
            track.play()
        } catch (_: Exception) {
        }
        playing = null
    }

    /** Drop only the filler, keeping any reply audio already queued. */
    private fun cutFiller() {
        val keep = ArrayList<Chunk>()
        q.drainTo(keep)
        val model = keep.filter { it.source == "model" }
        if (playing == "filler") {
            gen.incrementAndGet()
            try {
                track.pause(); track.flush(); track.play()
            } catch (_: Exception) {
            }
            Timeline.mark("filler_cut")
            playing = null
        }
        val g = gen.get()
        for (c in model) q.offer(Chunk(g, c.source, c.pcm))
    }

    fun release() {
        running = false
        thread.interrupt()
        runCatching { thread.join(500) }
        runCatching { track.pause(); track.flush(); track.release() }
    }

    private fun loop() {
        while (running) {
            val c = try {
                q.poll(200, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                break
            }
            if (c == null) {
                if (playing != null) {
                    if (playing == "filler") Timeline.mark("filler_end")
                    playing = null
                    listener?.onIdle()
                }
                continue
            }
            if (c.gen != gen.get()) continue // stale: dropped by stop()
            if (playing != c.source) {
                if (playing == "filler") Timeline.mark("filler_end")
                playing = c.source
                listener?.onReplyStart(c.source)
            }
            tap?.let { runCatching { it.write(c.pcm) } }
            try {
                track.write(c.pcm, 0, c.pcm.size)
            } catch (e: Exception) {
                Log.w("Player", "write failed: ${e.message}")
            }
            lastWriteAt = Timeline.now()
        }
    }
}
