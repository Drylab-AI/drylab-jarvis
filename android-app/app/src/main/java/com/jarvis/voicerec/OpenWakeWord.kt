package com.jarvis.voicerec

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * On-device "Jarvis" wake-word detector: a Kotlin port of openWakeWord's
 * streaming inference (https://github.com/dscripka/openWakeWord), running the
 * three bundled ONNX models (assets/oww/) with ONNX Runtime. Free, no account,
 * no key, audio never leaves the phone.
 *
 * Feed it 16 kHz mono 16-bit PCM in frames of exactly [FRAME_SAMPLES] (80 ms)
 * via [accept]; it returns the wake-word score (0..1) for that frame.
 *
 * Per frame (mirrors openwakeword.utils.AudioFeatures._streaming_features and
 * Model.predict; reference spec + test: tools/oww/oww_reference.py):
 *  1. raw audio buffer keeps the last 1760 samples (1280 new + 480 context)
 *  2. melspectrogram.onnx(raw) -> T x 32 frames, transformed x/10 + 2
 *     (8 frames per 80 ms once warm), appended to a 76-row mel window
 *  3. embedding_model.onnx(last 76 mel rows) -> 96-dim embedding
 *  4. hey_jarvis classifier(last 16 embeddings) -> score
 *  5. the first 5 scores after (re)start are forced to 0 (model warm-up)
 *
 * The public methods are synchronized so the mic thread and the service
 * thread can't race on accept/reset/close.
 *
 * Deliberately has no Android imports: models come from [loadModel] (the app
 * passes `assets.open(name).readBytes()`), so the identical file is verified
 * on the PC JVM against the Python reference (tools/oww/kt-check).
 */
class OpenWakeWord(
    loadModel: (String) -> ByteArray,
    keywordModel: String = "oww/hey_jarvis_v0.1.onnx",
) : AutoCloseable {

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 1280          // 80 ms

        private const val MEL_BINS = 32
        private const val MEL_WINDOW = 76       // mel frames per embedding
        private const val EMB_DIM = 96
        private const val EMB_WINDOW = 16       // embeddings per classifier call
        private const val EMB_STEP = 8          // mel frames between embeddings
        private const val RAW_CONTEXT = 160 * 3 // extra samples for the mel STFT
        private const val RAW_KEEP = FRAME_SAMPLES + RAW_CONTEXT // 1760
        private const val WARMUP_PREDICTIONS = 5
        private const val INIT_SECONDS = 10     // zero audio used to seed features
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(1)
        setInterOpNumThreads(1)
    }
    private val melSession: OrtSession
    private val embSession: OrtSession
    private val kwSession: OrtSession
    private val melInput: String
    private val embInput: String
    private val kwInput: String

    // Streaming state.
    private var raw = FloatArray(0)                       // <= RAW_KEEP samples
    private val mel = FloatArray(MEL_WINDOW * MEL_BINS)   // last 76 mel rows
    private val features = FloatArray(EMB_WINDOW * EMB_DIM) // last 16 embeddings
    private var predictions = 0

    // Seed features (embeddings of silence), restored on reset().
    private val seedFeatures: FloatArray

    private var closed = false

    init {
        fun load(name: String): OrtSession = env.createSession(loadModel(name), options)

        melSession = load("oww/melspectrogram.onnx")
        embSession = load("oww/embedding_model.onnx")
        kwSession = load(keywordModel)
        melInput = melSession.inputNames.first()
        embInput = embSession.inputNames.first()
        kwInput = kwSession.inputNames.first()

        seedFeatures = computeSeedFeatures()
        resetState()
    }

    /**
     * Process one 80 ms frame (exactly [FRAME_SAMPLES] samples of 16-bit PCM)
     * and return the wake-word score in 0..1.
     */
    @Synchronized
    fun accept(frame: ShortArray): Float {
        check(!closed) { "OpenWakeWord is closed" }
        require(frame.size == FRAME_SAMPLES) {
            "frame must be $FRAME_SAMPLES samples, got ${frame.size}"
        }

        // 1. raw buffer: append, keep the last RAW_KEEP samples.
        val combined = FloatArray(raw.size + frame.size)
        System.arraycopy(raw, 0, combined, 0, raw.size)
        for (i in frame.indices) combined[raw.size + i] = frame[i].toFloat()
        raw = if (combined.size > RAW_KEEP) {
            combined.copyOfRange(combined.size - RAW_KEEP, combined.size)
        } else {
            combined
        }

        // 2. new mel frames -> slide into the 76-row mel window.
        appendMel(melspectrogram(raw))

        // 3. embedding of the last 76 mel rows -> slide into feature window.
        val emb = embed(mel, batch = 1)
        System.arraycopy(features, EMB_DIM, features, 0, (EMB_WINDOW - 1) * EMB_DIM)
        System.arraycopy(emb, 0, features, (EMB_WINDOW - 1) * EMB_DIM, EMB_DIM)

        // 4. classifier on the last 16 embeddings.
        var score = classify(features)

        // 5. warm-up: ignore the first few predictions.
        predictions++
        if (predictions <= WARMUP_PREDICTIONS) score = 0f
        return score
    }

    /** Clear streaming state (e.g. after the assistant releases the mic). */
    @Synchronized
    fun reset() {
        if (!closed) resetState()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching { kwSession.close() }
        runCatching { embSession.close() }
        runCatching { melSession.close() }
        runCatching { options.close() }
        // OrtEnvironment is a process-wide singleton; do not close it.
    }

    // ---- internals --------------------------------------------------------

    private fun resetState() {
        raw = FloatArray(0)
        mel.fill(1f) // openWakeWord seeds its mel buffer with ones
        System.arraycopy(seedFeatures, 0, features, 0, features.size)
        predictions = 0
    }

    /** Embeddings of 10 s of silence, as openWakeWord seeds its features. */
    private fun computeSeedFeatures(): FloatArray {
        val spec = melspectrogram(FloatArray(SAMPLE_RATE * INIT_SECONDS))
        val frames = spec.size / MEL_BINS
        // Windows start every EMB_STEP frames; keep only full 76-frame windows.
        val starts = (0 until frames step EMB_STEP).filter { it + MEL_WINDOW <= frames }
        // Only the last EMB_WINDOW embeddings are ever fed to the classifier.
        val used = starts.takeLast(EMB_WINDOW)
        val batch = FloatArray(used.size * MEL_WINDOW * MEL_BINS)
        used.forEachIndexed { b, start ->
            System.arraycopy(
                spec, start * MEL_BINS,
                batch, b * MEL_WINDOW * MEL_BINS,
                MEL_WINDOW * MEL_BINS,
            )
        }
        val embs = embed(batch, batch = used.size)
        // Right-align into a 16-row buffer (pad with zeros if fewer; never in
        // practice: 10 s yields 116 windows).
        val out = FloatArray(EMB_WINDOW * EMB_DIM)
        System.arraycopy(embs, 0, out, out.size - embs.size, embs.size)
        return out
    }

    /** melspectrogram.onnx: samples -> (T*32) floats, transformed x/10 + 2. */
    private fun melspectrogram(samples: FloatArray): FloatArray {
        OnnxTensor.createTensor(
            env, FloatBuffer.wrap(samples), longArrayOf(1, samples.size.toLong())
        ).use { input ->
            melSession.run(mapOf(melInput to input)).use { result ->
                val out = (result.get(0) as OnnxTensor).floatBuffer
                val spec = FloatArray(out.remaining())
                out.get(spec)
                for (i in spec.indices) spec[i] = spec[i] / 10f + 2f
                return spec
            }
        }
    }

    /** Slide [newRows] (k*32 floats) into the 76-row mel window. */
    private fun appendMel(newRows: FloatArray) {
        val k = newRows.size / MEL_BINS
        if (k <= 0) return
        if (k >= MEL_WINDOW) {
            System.arraycopy(newRows, (k - MEL_WINDOW) * MEL_BINS, mel, 0, mel.size)
            return
        }
        val keep = (MEL_WINDOW - k) * MEL_BINS
        System.arraycopy(mel, k * MEL_BINS, mel, 0, keep)
        System.arraycopy(newRows, 0, mel, keep, k * MEL_BINS)
    }

    /** embedding_model.onnx: batch x (76x32) mel windows -> batch x 96. */
    private fun embed(windows: FloatArray, batch: Int): FloatArray {
        OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(windows),
            longArrayOf(batch.toLong(), MEL_WINDOW.toLong(), MEL_BINS.toLong(), 1),
        ).use { input ->
            embSession.run(mapOf(embInput to input)).use { result ->
                val out = (result.get(0) as OnnxTensor).floatBuffer
                val emb = FloatArray(out.remaining())
                out.get(emb)
                return emb
            }
        }
    }

    /** Wake-word classifier: 16 x 96 embeddings -> score. */
    private fun classify(feats: FloatArray): Float {
        OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(feats),
            longArrayOf(1, EMB_WINDOW.toLong(), EMB_DIM.toLong()),
        ).use { input ->
            kwSession.run(mapOf(kwInput to input)).use { result ->
                return (result.get(0) as OnnxTensor).floatBuffer.get(0)
            }
        }
    }
}
