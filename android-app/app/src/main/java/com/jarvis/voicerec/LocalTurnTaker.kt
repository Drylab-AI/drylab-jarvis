package com.jarvis.voicerec

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * The on-phone "interaction layer" (after Thinking Machines' interaction
 * models): it watches the mic locally, so the reactions that must feel
 * instant don't wait for a cloud round trip.
 *
 *  - Cut-in: the user starts talking while Jarvis speaks -> stop playback
 *    now, before Gemini's own `interrupted` message arrives.
 *  - Filler: the user finishes a turn and no reply audio arrives within
 *    [fillerAfterMs] -> play a short "hmm" / "let me see" so the silence
 *    never feels dead. The real reply cuts it.
 *
 * Gemini stays the "background model": it decides what to say. This layer
 * never answers anything itself.
 *
 * Speech detection is Silero VAD (MIT, ~2 MB, 32 ms frames, ~1 ms on CPU),
 * run on the onnxruntime already in the APK for the wake word.
 */
class LocalTurnTaker(
    modelBytes: ByteArray,
    private val actions: Actions,
) : AutoCloseable {

    interface Actions {
        /** Is the speaker playing model or filler audio right now? */
        fun assistantSpeaking(): Boolean
        /** Phone time the last reply audio was played (0 = never). */
        fun lastReplyAt(): Long
        fun cutIn()
        fun playFiller(): Boolean
    }

    companion object {
        const val FRAME = 512             // 32 ms at 16 kHz (Silero's size)
        private const val CONTEXT = 64
        private const val SPEECH_ON = 0.6f
        private const val SPEECH_OFF = 0.35f

        /** Speech needs this long to count as the user starting a turn. */
        private const val START_MS = 160
        /**
         * Loud speech needed while Jarvis talks before we stop it. Measured:
         * at 220 ms the cut took 207 ms from the user's first sound; 3 Silero
         * frames (96 ms) plus the RMS gate keeps echo out (no_self_interrupt
         * scenario) while cutting within ~110 ms.
         */
        private const val CUTIN_MS = 96
        /**
         * Silence after speech before we call the turn over (a local guess,
         * only used to time the filler; Gemini still decides the real turn
         * end). Measured: Silero keeps scoring a trailing breath as speech
         * for ~100 ms after the words, so the detected end lags the true end.
         */
        private const val END_MS = 192
        /**
         * Filler goes out this long after the user really stopped (turnEndAt
         * is back-dated by the silence already counted). Gemini's reply on
         * 3.8 lands at ~1.1-1.4 s, so a filler at ~0.3 s always fills a gap
         * and never collides with a fast answer.
         */
        const val FILLER_AFTER_MS = 260L
        /** Turns shorter than this ("yes", "stop") get no filler. */
        private const val SHORT_TURN_MS = 700
        /** Never two fillers closer than this. */
        private const val FILLER_GAP_MS = 4_000L
        /**
         * RMS above which a frame counts as the user while Jarvis talks.
         * With VOICE_COMMUNICATION AEC the echo residual on this phone sits
         * far below a person speaking at arm's length; the bar keeps a
         * loud reply from triggering a self-interrupt.
         */
        private const val CUTIN_MIN_RMS = 900.0
    }

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(modelBytes, OrtSession.SessionOptions())
    private var state = FloatArray(2 * 1 * 128)
    private val context = FloatArray(CONTEXT)
    private val pending = ShortArray(FRAME)
    private var pendingN = 0

    private var inSpeech = false
    private var speechMs = 0
    private var loudMs = 0
    private var silenceMs = 0
    private var turnSpeechMs = 0
    @Volatile private var turnEndAt = 0L
    private var fillerArmed = false
    private var lastFillerAt = 0L
    private var cutInThisReply = false

    /** Feed mic audio (any frame size). Call from the mic thread only. */
    @Synchronized
    fun accept(samples: ShortArray) {
        var i = 0
        while (i < samples.size) {
            val n = minOf(FRAME - pendingN, samples.size - i)
            System.arraycopy(samples, i, pending, pendingN, n)
            pendingN += n
            i += n
            if (pendingN == FRAME) {
                onFrame(pending)
                pendingN = 0
            }
        }
        maybeFiller()
    }

    private fun onFrame(frame: ShortArray) {
        val p = prob(frame)
        val rms = rms(frame)
        val ms = FRAME * 1000 / 16_000
        val speaking = actions.assistantSpeaking()
        if (!speaking) cutInThisReply = false

        val voiced = if (inSpeech) p >= SPEECH_OFF else p >= SPEECH_ON
        if (voiced) {
            speechMs += ms
            silenceMs = 0
            if (rms >= CUTIN_MIN_RMS) loudMs += ms else loudMs = 0
        } else {
            silenceMs += ms
            if (silenceMs > 96) { speechMs = 0; loudMs = 0 }
        }

        // Cut-in: sustained, loud speech while Jarvis is talking.
        if (speaking && !cutInThisReply && loudMs >= CUTIN_MS) {
            cutInThisReply = true
            Timeline.mark("local_cut_in", "p=%.2f rms=%.0f".format(p, rms))
            actions.cutIn()
            inSpeech = true
            turnSpeechMs = speechMs
            fillerArmed = false
            return
        }

        if (!inSpeech && !speaking && speechMs >= START_MS) {
            inSpeech = true
            turnSpeechMs = speechMs
            fillerArmed = false
            Timeline.mark("local_speech_start")
        } else if (inSpeech) {
            if (voiced) turnSpeechMs += ms
            if (silenceMs >= END_MS) {
                inSpeech = false
                // Back-date to when the silence began, plus Silero's trailing
                // lag, so the filler is timed from the user's real stop.
                turnEndAt = Timeline.now() - silenceMs - 96
                Timeline.mark("local_turn_end", "speech_ms=$turnSpeechMs")
                fillerArmed = turnSpeechMs >= SHORT_TURN_MS
            }
        }
    }

    /**
     * After the user's turn ends, give the model [FILLER_AFTER_MS] to start
     * replying; if it hasn't, fill the gap once.
     */
    private fun maybeFiller() {
        if (!fillerArmed) return
        val now = Timeline.now()
        if (actions.lastReplyAt() > turnEndAt) { fillerArmed = false; return }
        if (now - turnEndAt < FILLER_AFTER_MS) return
        fillerArmed = false
        if (now - lastFillerAt < FILLER_GAP_MS) return
        // Too late to be useful: a filler 1 s+ after the turn sounds odd.
        if (now - turnEndAt > FILLER_AFTER_MS + 400) return
        if (actions.playFiller()) lastFillerAt = now
    }

    private fun prob(frame: ShortArray): Float {
        val input = FloatArray(CONTEXT + FRAME)
        System.arraycopy(context, 0, input, 0, CONTEXT)
        for (k in 0 until FRAME) input[CONTEXT + k] = frame[k] / 32768f
        System.arraycopy(input, FRAME, context, 0, CONTEXT)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, (CONTEXT + FRAME).toLong())).use { x ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, 128)).use { st ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(16_000)), longArrayOf()).use { sr ->
                    session.run(mapOf("input" to x, "state" to st, "sr" to sr)).use { out ->
                        @Suppress("UNCHECKED_CAST")
                        val p = (out[0].value as Array<FloatArray>)[0][0]
                        val ns = out[1].value
                        if (ns is Array<*>) {
                            // [2][1][128]
                            var k = 0
                            for (a in ns) for (b in a as Array<*>) for (v in b as FloatArray) state[k++] = v
                        }
                        return p
                    }
                }
            }
        }
    }

    private fun rms(frame: ShortArray): Double {
        var s = 0.0
        for (v in frame) s += v.toDouble() * v
        return Math.sqrt(s / frame.size)
    }

    override fun close() {
        runCatching { session.close() }
    }
}
