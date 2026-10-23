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
}
