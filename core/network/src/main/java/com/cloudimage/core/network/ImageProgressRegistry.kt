package com.cloudimage.core.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.util.concurrent.ConcurrentHashMap

/**
 * One progress snapshot of an image download: how many body bytes have
 * arrived, how many the response announced ([UNKNOWN_TOTAL] when the
 * server did not declare a length), and whether the body is fully read.
 */
data class ImageProgress(
    val bytesRead: Long,
    val totalBytes: Long,
    val done: Boolean,
) {
    /** Bytes over total as 0f..1f, or null while the total is unknown. */
    val fraction: Float?
        get() =
            if (totalBytes > 0) {
                (bytesRead.toFloat() / totalBytes).coerceIn(0f, 1f)
            } else {
                null
            }

    companion object {
        const val UNKNOWN_TOTAL: Long = -1L
    }
}

/**
 * Cross-cutting byte progress for Coil image loads. The app's
 * [ImageLoaderFactory][coil.ImageLoaderFactory] installs
 * [interceptor] on the OkHttp client behind the app-wide Coil loader; the
 * fullscreen viewer (and anything else that cares) subscribes with
 * [observe] for the URL it is about to show.
 *
 * Deliberately a process-wide registry rather than an injected service:
 * it is the observational side of a transport concern, exactly like Coil's
 * own memory and disk caches, and must be reachable from both the OkHttp
 * layer (any dispatcher thread) and composition without threading a
 * dependency graph through either.
 *
 * Lifecycle: [observe] registers (lazily) an observer for a URL; a response
 * for that URL then feeds it byte counts as its body streams through; the
 * consumer calls [reset] when it leaves the screen so a later visit starts
 * fresh. URLs nobody observes pass through the interceptor untouched — no
 * wrapping, no allocation, thumbnails included.
 */
object ImageProgressRegistry {
    private val observers = ConcurrentHashMap<String, MutableStateFlow<ImageProgress?>>()

    /**
     * The progress flow for [url]. The value is null until the response
     * starts streaming, then updates as bytes arrive; a completed body ends
     * on `done = true`. Cache hits never fire the interceptor, which simply
     * means the value stays null — instant loads have nothing to report.
     */
    fun observe(url: String): StateFlow<ImageProgress?> = observers.getOrPut(url) { MutableStateFlow(null) }

    /** Drops the observer for [url]; the next [observe] starts from scratch. */
    fun reset(url: String) {
        observers.remove(url)
    }

    /** The interceptor to install on the Coil loader's OkHttp client. */
    fun interceptor(): Interceptor = ImageProgressInterceptor

    internal fun update(
        url: String,
        bytesRead: Long,
        totalBytes: Long,
    ) {
        observers[url]?.let { flow ->
            val total = if (totalBytes >= 0) totalBytes else ImageProgress.UNKNOWN_TOTAL
            flow.value = ImageProgress(bytesRead = bytesRead, totalBytes = total, done = false)
        }
    }

    /** Marks the current snapshot, if any, as finished — the belt to the body reader's suspenders. */
    fun finish(url: String) {
        observers[url]?.let { flow ->
            val current = flow.value ?: return@let
            flow.value = current.copy(done = true)
        }
    }

    private object ImageProgressInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val url = chain.request().url.toString()
            val response = chain.proceed(chain.request())
            // Nobody is watching this URL — pass the body through untouched.
            if (!observers.containsKey(url)) return response
            val body = response.body ?: return response
            return response.newBuilder().body(ProgressResponseBody(url, body)).build()
        }
    }

    /**
     * A response body that counts bytes as they stream past and reports
     * them to the registry. Reads happen on OkHttp's dispatcher thread;
     * [MutableStateFlow] conflation keeps recomposition cheap.
     */
    private class ProgressResponseBody(
        private val url: String,
        private val delegate: ResponseBody,
    ) : ResponseBody() {
        private val totalBytes: Long = delegate.contentLength()

        private val countingSource: BufferedSource =
            object : ForwardingSource(delegate.source()) {
                private var bytesRead = 0L

                override fun read(
                    sink: Buffer,
                    byteCount: Long,
                ): Long {
                    val count = super.read(sink, byteCount)
                    if (count > 0) {
                        bytesRead += count
                        update(url, bytesRead, totalBytes)
                    } else if (count == -1L) {
                        finish(url)
                    }
                    return count
                }
            }.buffer()

        override fun contentType() = delegate.contentType()

        override fun contentLength(): Long = totalBytes

        override fun source(): BufferedSource = countingSource
    }
}
