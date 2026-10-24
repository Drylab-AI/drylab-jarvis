package com.jarvis.voicerec

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Turn-level timeline for the assistant, logged under the `JARVIS_T` tag as
 * `<elapsedRealtime ms> <event> <detail>`.
 *
 * Both the latency numbers and the automated tests (tests/e2e/run_e2e.py)
 * read these lines, so every timestamp comes from the phone's own clock: the
 * moment the user stops speaking and the moment the reply is actually played
 * are measured by the same clock, and network clock skew never enters.
 *
 * Only logged per turn or per state change, never per audio chunk.
 */
object Timeline {
    const val TAG = "JARVIS_T"

    fun now(): Long = SystemClock.elapsedRealtime()

    fun mark(event: String, detail: String = "") {
        Log.i(TAG, "${now()} $event $detail".trimEnd())
    }
}

/**
 * Debug-only test mode, enabled by the harness creating [FLAG_FILE] with adb.
 *
 * When it is on, the assistant's microphone is replaced by [ScriptedMic]:
 * silence plus whatever clips the harness injects. Everything downstream
 * (local voice detection, fillers, cut-in, the server and Gemini) receives
 * exactly the audio a real speaker would produce, at real-time speed, so the
 * whole conversation can be tested without anybody talking.
 *
 * It is gated on a debuggable build AND the flag file, so a release build or
 * a phone without the file can never be driven from outside.
 */
object TestMode {
    /** Broadcast action the harness sends with `am broadcast -p <pkg>`. */
    const val ACTION_INJECT = "com.jarvis.voicerec.TEST_INJECT"

    /**
     * Test files live in the app's private files dir
     * (/data/data/com.jarvis.voicerec/files/jt). The harness copies clips in
     * as root and chowns them to the app. Two other places were tried and
     * failed: /data/local/tmp is not writable by the app, and files adb
     * pushes to /sdcard/Android/data/<pkg> are owned by the shell user, which
     * the app is not allowed to read through FUSE.
     */
    fun dir(ctx: Context): File = File(ctx.filesDir, "jt")

    private fun flag(ctx: Context): File = File(dir(ctx), "test-mode")

    fun debuggable(ctx: Context): Boolean =
        (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    fun enabled(ctx: Context): Boolean =
        debuggable(ctx) && runCatching { flag(ctx).exists() }.getOrDefault(false)

    /**
     * Some tests (echo / self-interruption) need the real microphone while
     * keeping the test outputs. The flag file then contains "realmic".
     */
    fun realMic(ctx: Context): Boolean =
        enabled(ctx) && runCatching { flag(ctx).readText().contains("realmic") }.getOrDefault(false)
}

/**
 * Stand-in microphone for test mode: produces 16 kHz mono PCM in fixed-size
 * frames, either from an injected clip or silence.
 *
 * A clip starts either immediately (`now`) or relative to the next time the
 * assistant starts speaking (`speaking`), plus a delay. The second form is
 * what makes a cut-in test reproducible: "start talking 1.5 s after Jarvis
 * starts answering".
 */
class ScriptedMic {

    private class Clip(
        val name: String,
        val samples: ShortArray,
        val trigger: String,
        val delayMs: Long,
        enqueuedAt: Long,
    ) {
        /** Absolute start time once known; null while waiting for `speaking`. */
        @Volatile var startAt: Long? = if (trigger == "speaking") null else enqueuedAt + delayMs
        var pos = 0
        var started = false
    }

    private val clips = CopyOnWriteArrayList<Clip>()

    /** Queue a raw s16le 16 kHz mono file. Returns false if it can't be read. */
    fun enqueue(path: String, name: String, trigger: String, delayMs: Long): Boolean {
        val f = File(path)
        if (!f.canRead()) {
            Log.w(Timeline.TAG, "cannot read clip $path")
            return false
        }
        val bytes = f.readBytes()
        val samples = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        clips.add(Clip(name, samples, trigger, delayMs, Timeline.now()))
        Timeline.mark("mic_clip_queued", "name=$name trigger=$trigger delay=$delayMs")
        return true
    }

    /** Called when the assistant starts playing a reply: arms `speaking` clips. */
    fun onAssistantSpeaking(at: Long) {
        for (c in clips) {
            if (c.startAt == null) c.startAt = at + c.delayMs
        }
    }

    fun clear() = clips.clear()

    /**
     * Fill [out] with the next frame. Clips never overlap: the earliest one
     * that is due plays to the end before the next starts.
     */
    fun next(out: ShortArray) {
        val now = Timeline.now()
        val c = clips.firstOrNull { (it.startAt ?: Long.MAX_VALUE) <= now }
        if (c == null) {
            out.fill(0)
            return
        }
        if (!c.started) {
            c.started = true
            Timeline.mark("mic_clip_start", "name=${c.name}")
        }
        val n = minOf(out.size, c.samples.size - c.pos)
        System.arraycopy(c.samples, c.pos, out, 0, n)
        if (n < out.size) out.fill(0, n, out.size)
        c.pos += n
        if (c.pos >= c.samples.size) {
            clips.remove(c)
            // The clips are trimmed of trailing silence, so this is the exact
            // moment the "user" stopped speaking: turn latency starts here.
            Timeline.mark("mic_clip_end", "name=${c.name}")
        }
    }
}
