package com.jarvis.voicerec

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridges producer callbacks (CameraX frames, AAC packets) to an OkHttp
 * streaming request body. The producer calls [offerFrame]; the OkHttp writer
 * thread calls [take] in a loop until it returns null (stream closed).
 *
 * The queue is bounded and drops the oldest frame when full, so a slow network
 * never backs up memory or stalls the camera. [close] unblocks the writer.
 */
class StreamPump(private val capacity: Int = 8) {
    private val queue = LinkedBlockingQueue<ByteArray>(capacity)
    private val closed = AtomicBoolean(false)

    /** Producer side: enqueue a frame, dropping the oldest if the queue is full. */
    fun offerFrame(frame: ByteArray) {
        if (closed.get()) return
        if (!queue.offer(frame)) {
            // Full: drop oldest to keep latency low, then enqueue the new one.
            queue.poll()
            queue.offer(frame)
        }
    }

    /**
     * Consumer side: block for the next frame. Returns null when the stream is
     * closed and drained, which tells the writer loop to finish.
     */
    fun take(): ByteArray? {
        while (true) {
            if (closed.get()) {
                return queue.poll()
            }
            val f = queue.poll(200, TimeUnit.MILLISECONDS)
            if (f != null) return f
        }
    }

    fun close() {
        closed.set(true)
    }
}
