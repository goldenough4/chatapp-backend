package com.gold.chatapp

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import java.io.IOException

/**
 * ProgressRequestBody.kt
 * Reports upload progress to the UI for images, videos, and voice notes.
 */
class ProgressRequestBody(
    private val delegate: RequestBody,
    private val listener: UploadProgressListener,
) : RequestBody() {

    interface UploadProgressListener {
        fun onProgress(bytesWritten: Long, contentLength: Long, elapsedMs: Long)
    }

    private val startTime = System.currentTimeMillis()

    override fun contentType(): MediaType? = delegate.contentType()

    @Throws(IOException::class)
    override fun contentLength(): Long = delegate.contentLength()

    @Throws(IOException::class)
    override fun writeTo(sink: BufferedSink) {
        val countingSink = object : ForwardingSink(sink) {
            private var totalBytesWritten = 0L

            @Throws(IOException::class)
            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                totalBytesWritten += byteCount
                // Post progress back to the listener with elapsed time
                val elapsed = System.currentTimeMillis() - startTime
                listener.onProgress(totalBytesWritten, contentLength(), elapsed)
            }
        }

        val bufferedSink = countingSink.buffer()
        delegate.writeTo(bufferedSink)
        bufferedSink.flush()
    }
}
