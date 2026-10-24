package com.jarvis.voicerec

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

/**
 * Shared YUV_420_888 -> NV21 -> JPEG conversion for CameraX ImageAnalysis
 * frames. Used by both CameraService (MJPEG upload) and AssistantService
 * (sending frames to Gemini Live).
 */
object CameraJpeg {
    fun toJpeg(image: ImageProxy, quality: Int): ByteArray? {
        val nv21 = yuv420ToNv21(image) ?: return null
        val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, image.width, image.height), quality, out)
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

        var pos = 0
        val yBuf = yPlane.buffer
        val yRowStride = yPlane.rowStride
        for (row in 0 until h) {
            val start = row * yRowStride
            yBuf.position(start)
            yBuf.get(nv21, pos, w)
            pos += w
        }

        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val uvRowStride = uPlane.rowStride
        val uvPixStride = uPlane.pixelStride
        val chromaH = h / 2
        val chromaW = w / 2

        // Fast path: most Pixel camera HALs hand out semi-planar chroma where
        // the V plane already IS interleaved V,U,V,U (NV21) with U one byte
        // behind. Then each chroma row is a single bulk copy instead of a
        // per-byte loop. The per-byte loop cost ~100 ms per 640x480 frame and
        // capped the assistant camera at ~8 fps.
        if (uvPixStride == 2 && vPlane.pixelStride == 2 &&
            vPlane.rowStride == uvRowStride && isNv21Layout(uBuf, vBuf)
        ) {
            val rowBytes = chromaW * 2 - 1 // last U of the row lives in the U plane
            for (row in 0 until chromaH) {
                val off = row * uvRowStride
                vBuf.position(off)
                vBuf.get(nv21, pos, minOf(rowBytes, vBuf.remaining()))
                nv21[pos + rowBytes] = uBuf.get(off + (chromaW - 1) * 2)
                pos += chromaW * 2
            }
            vBuf.rewind()
            return nv21
        }
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

    /**
     * True when the U and V buffers are views of one interleaved VU block
     * (U = V + 1 byte), checked by value at a few points: same underlying
     * memory means vBuf[i+1] == uBuf[i] for every i.
     */
    private fun isNv21Layout(u: java.nio.ByteBuffer, v: java.nio.ByteBuffer): Boolean {
        val n = minOf(u.limit(), v.limit() - 1)
        if (n < 64) return false
        for (i in intArrayOf(0, 2, n / 3 and 0x7ffffffe, n / 2 and 0x7ffffffe, (n - 2) and 0x7ffffffe)) {
            if (v.get(i + 1) != u.get(i)) return false
        }
        return true
    }
}
