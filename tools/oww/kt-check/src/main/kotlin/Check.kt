import com.jarvis.voicerec.OpenWakeWord
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Stream raw 16 kHz s16le clips through the app's OpenWakeWord in 1280-sample
 * frames (with 1 s of silence padding either side, matching the Python
 * reference) and print per-frame scores.
 */
fun main(args: Array<String>) {
    require(args.size >= 2) { "usage: <models dir> <clip.raw> [clip.raw ...]" }
    val modelsDir = File(args[0])
    val oww = OpenWakeWord({ name -> File(modelsDir, name.substringAfter("oww/")).readBytes() })

    for (path in args.drop(1)) {
        val bytes = File(path).readBytes()
        val clip = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(clip)
        val pad = ShortArray(16_000)
        val pcm = pad + clip + pad

        oww.reset()
        val scores = ArrayList<Float>()
        var i = 0
        val t0 = System.nanoTime()
        while (i + OpenWakeWord.FRAME_SAMPLES <= pcm.size) {
            scores += oww.accept(pcm.copyOfRange(i, i + OpenWakeWord.FRAME_SAMPLES))
            i += OpenWakeWord.FRAME_SAMPLES
        }
        val ms = (System.nanoTime() - t0) / 1e6 / scores.size
        val max = scores.max()
        println("$path frames ${scores.size} max %.4f at ${scores.indexOf(max)} (%.2f ms/frame)".format(max, ms))
        println("   " + scores.joinToString(" ") { "%.3f".format(it) })
    }
    oww.close()
}
