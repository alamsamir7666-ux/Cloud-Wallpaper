package com.cloudimage.core.network

import com.cloudimage.core.network.NetworkResult.Failure
import com.cloudimage.core.network.NetworkResult.Success
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A completed HTTP exchange: status code, response headers and raw body
 * bytes. Carried by [CloudimageHttpClient.getRaw] — the seam the extension
 * engine adapts into the plugin-facing HTTP facade.
 */
class HttpPayload(
    val statusCode: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
) {
    /** The body decoded as UTF-8 text; empty for empty bodies. */
    val bodyText: String get() = String(body, Charsets.UTF_8)

    /** HTTP-level success: any 2xx status. */
    val isSuccessful: Boolean get() = statusCode in 200..299
}

/**
 * The single HTTP entry point every provider goes through — built-in providers
 * in-process, and loaded extensions through the injected client facade.
 *
 * Responsibilities:
 * - identify the app to servers with a stable [User-Agent][USER_AGENT],
 * - turn every failure mode into a typed [NetworkResult], never an exception,
 * - parse JSON bodies through the shared, forgiving [Json] configuration,
 * - solve Cloudflare challenges on the app's side: a challenged request
 *   is replayed once under a WebView-earned clearance, and when even a
 *   replay cannot get through — a fingerprint-strict zone, or a challenge
 *   no WebView clearance satisfies — the document itself is fetched
 *   through the WebView (see [cloudflare]).
 */
@Singleton
class CloudimageHttpClient
    @Inject
    constructor(
        private val okHttpClient: OkHttpClient,
        private val json: Json,
        private val cloudflare: CloudflareBypasser = CloudflareBypasser.DISABLED,
    ) {
        /**
         * Performs a GET and returns the full raw exchange — status, headers
         * and body — whatever the status code is. This is the seam behind the
         * provider facade; transport failures surface as
         * [NetworkResult.Failure] like everywhere else.
         *
         * A Cloudflare challenge answer no longer ends the exchange — the
         * ladder has three rungs. A clearance already on file for the host
         * rides the first attempt. A challenged request asks [cloudflare]
         * for a fresh clearance and replays under it once. When the replay
         * is STILL challenged — or no clearance could be earned at all —
         * the last rung loads the URL in the WebView and returns the
         * document the browser engine rendered, the one answer a
         * fingerprint-strict zone cannot distinguish from a user. When the
         * WebView cannot produce a page either, the challenge response
         * itself is returned like any other non-2xx: per the facade
         * contract, providers decide how to treat it, and their readable
         * failures travel the app's source-failure banner as before.
         */
        suspend fun getRaw(
            url: String,
            extraHeaders: Map<String, String> = emptyMap(),
        ): NetworkResult<HttpPayload> =
            withContext(Dispatchers.IO) {
                try {
                    val state = cloudflare.bypassStateFor(url)
                    var stale = state
                    var payload = execute(url, extraHeaders, state)
                    if (CloudflareChallenge.isChallenge(payload)) {
                        val bypass = cloudflare.solve(url, staleState = stale)
                        if (bypass != null) {
                            stale = bypass
                            payload = execute(url, extraHeaders, bypass)
                        }
                    }
                    if (CloudflareChallenge.isChallenge(payload)) {
                        payload = fetchThroughWebView(url, extraHeaders, stale, payload)
                    }
                    Success(payload)
                } catch (e: SocketTimeoutException) {
                    Failure(NetworkError.Timeout)
                } catch (e: IOException) {
                    Failure(NetworkError.Io(e))
                }
            }

        /**
         * The ladder's last rung, reached only when the request stayed
         * challenged through a replay: fetch the document through the
         * WebView. The fetched HTML wins outright; a page with no HTML but
         * a fresher clearance (earned while this request waited) gets one
         * more replay under it; anything else falls back to the challenged
         * [fallback] response, the honest result the caller already had.
         */
        private suspend fun fetchThroughWebView(
            url: String,
            extraHeaders: Map<String, String>,
            stale: CloudflareBypass?,
            fallback: HttpPayload,
        ): HttpPayload {
            val page = cloudflare.webViewFetch(url, staleState = stale) ?: return fallback
            if (page.html != null) {
                return HttpPayload(
                    statusCode = 200,
                    headers = emptyMap(),
                    body = page.html.toByteArray(),
                )
            }
            val clearance = page.clearance ?: return fallback
            val replayed = execute(url, extraHeaders, clearance)
            return if (CloudflareChallenge.isChallenge(replayed)) fallback else replayed
        }

        /**
         * Runs one GET and buffers the exchange. [bypass] — the clearance a
         * request carries when the host has one on file, or a freshly earned
         * one on the replay — is applied LAST: a clearance is bound to the
         * User-Agent that earned it, so it overrides both the host agent and
         * any provider browser UA for that host, and its cookies ride the
         * same request.
         *
         * A replayed request also drops the provider's `sec-ch-ua*` client
         * hints (v1.2.4): those headers claim a specific Chrome version —
         * WallpaperFlare's say 131 — and under a clearance the User-Agent is
         * the WebView identity the cookies were earned with. Client hints
         * contradicting the User-Agent are exactly the kind of incoherence
         * Cloudflare's bot scoring reads, so the replay stays silent about
         * browser versions it cannot honestly claim.
         *
         * [onProgress], when set, streams 2xx bodies in [PROGRESS_CHUNK_BYTES]
         * chunks and reports the running byte count plus the Content-Length
         * when there is one; every other body (error pages, challenges) is
         * buffered as before.
         */
        private suspend fun execute(
            url: String,
            extraHeaders: Map<String, String>,
            bypass: CloudflareBypass?,
            onProgress: ((bytesRead: Long, totalBytes: Long?) -> Unit)? = null,
        ): HttpPayload {
            val replayHeaders =
                if (bypass == null) {
                    extraHeaders
                } else {
                    extraHeaders.filterKeys { name -> !name.startsWith(CLIENT_HINT_PREFIX, ignoreCase = true) }
                }
            val request =
                Request
                    .Builder()
                    .url(url)
                    .header(HEADER_USER_AGENT, USER_AGENT)
                    .apply {
                        for ((name, value) in replayHeaders) {
                            header(name, value)
                        }
                        if (bypass != null) {
                            header(HEADER_USER_AGENT, bypass.userAgent)
                            header(HEADER_COOKIE, bypass.cookieHeader)
                        }
                    }.build()
            return okHttpClient.newCall(request).await().use { response ->
                val body = response.body
                val bytes =
                    when {
                        body == null -> ByteArray(0)
                        onProgress != null && response.code in 200..299 -> body.readBytesWithProgress(onProgress)
                        else -> body.bytes()
                    }
                HttpPayload(
                    statusCode = response.code,
                    headers = response.headers.toMultimap(),
                    body = bytes,
                )
            }
        }

        /**
         * Performs a GET and returns the body, failing on non-2xx statuses.
         * Implemented on [getRaw]; [extraHeaders] are appended to the
         * User-Agent the client always sends.
         */
        suspend fun get(
            url: String,
            extraHeaders: Map<String, String> = emptyMap(),
        ): NetworkResult<String> {
            val raw = getRaw(url, extraHeaders)
            return when (raw) {
                is Failure -> raw
                is Success ->
                    if (raw.value.isSuccessful) {
                        Success(raw.value.bodyText)
                    } else {
                        Failure(NetworkError.Http(raw.value.statusCode, url))
                    }
            }
        }

        /**
         * Performs a GET and returns the raw body bytes — for downloads of
         * full-resolution images (apply-as-wallpaper, save-to-gallery, share).
         *
         * Same failure taxonomy as [get]; the body is buffered in memory, which
         * is fine for wallpaper-sized files (single-digit megabytes).
         */
        suspend fun download(url: String): NetworkResult<ByteArray> = download(url) { _, _ -> }

        /**
         * [download] with live byte progress: [onProgress] receives the bytes
         * read so far and the total when the server states one (null when the
         * response carries no usable Content-Length — the caller degrades to an
         * indeterminate indicator).
         *
         * Progress only flows on 2xx bodies: error pages and Cloudflare
         * challenges are tiny and buffered as usual, so a solve-and-replay
         * never emits misleading byte counts.
         */
        suspend fun download(
            url: String,
            onProgress: (bytesRead: Long, totalBytes: Long?) -> Unit,
        ): NetworkResult<ByteArray> =
            withContext(Dispatchers.IO) {
                try {
                    val state = cloudflare.bypassStateFor(url)
                    val first = execute(url, emptyMap(), state, onProgress)
                    val payload =
                        if (!CloudflareChallenge.isChallenge(first)) {
                            first
                        } else {
                            val bypass = cloudflare.solve(url, staleState = state)
                            if (bypass == null) {
                                first
                            } else {
                                execute(url, emptyMap(), bypass, onProgress)
                            }
                        }
                    if (payload.isSuccessful) {
                        Success(payload.body)
                    } else {
                        Failure(NetworkError.Http(payload.statusCode, url))
                    }
                } catch (e: SocketTimeoutException) {
                    Failure(NetworkError.Timeout)
                } catch (e: IOException) {
                    Failure(NetworkError.Io(e))
                }
            }

        /** Performs a GET and decodes the JSON body into [T]. */
        suspend fun <T> getJson(
            url: String,
            deserializer: KSerializer<T>,
        ): NetworkResult<T> =
            when (val raw = get(url)) {
                is Failure -> raw
                is Success ->
                    try {
                        Success(json.decodeFromString(deserializer, raw.value))
                    } catch (e: SerializationException) {
                        Failure(NetworkError.Serialization(e))
                    } catch (e: IllegalArgumentException) {
                        Failure(NetworkError.Serialization(e))
                    }
            }

        companion object {
            private const val HEADER_USER_AGENT = "User-Agent"

            private const val HEADER_COOKIE = "Cookie"

            /** The client-hint family prefix a clearance replay must not contradict. */
            internal const val CLIENT_HINT_PREFIX = "sec-ch-ua"

            /**
             * How many bytes one progress tick covers — wallpaper-sized
             * files report a few dozen ticks, not one per read.
             */
            internal const val PROGRESS_CHUNK_BYTES = 64L * 1024

            /** Sent with every request; some public APIs require identification. */
            internal const val USER_AGENT =
                "Cloudimage/1.0 (Android; +https://github.com/alamsamir7666-ux/Cloud-Wallpaper)"
        }
    }

/**
 * Streams [this] body into a byte array, reporting the running count to
 * [onProgress] once per [CloudimageHttpClient.PROGRESS_CHUNK_BYTES] read.
 * The declared Content-Length (when positive) rides along as the total;
 * chunked or unlengthed bodies report a null total and the caller decides
 * how to render that honestly.
 */
private fun okhttp3.ResponseBody.readBytesWithProgress(onProgress: (bytesRead: Long, totalBytes: Long?) -> Unit): ByteArray {
    val total = contentLength().takeIf { it > 0 }
    val source = source()
    val output = okio.Buffer()
    val chunk = okio.Buffer()
    var read = 0L
    while (true) {
        val count = source.read(chunk, CloudimageHttpClient.PROGRESS_CHUNK_BYTES)
        if (count == -1L) break
        read += count
        output.write(chunk, count)
        chunk.clear()
        onProgress(read, total)
    }
    return output.readByteArray()
}

/**
 * Suspends until the OkHttp call completes; cancelling the coroutine cancels
 * the HTTP call. OkHttp ships no coroutine adapter, so we bridge via
 * [suspendCancellableCoroutine] — the standard pattern.
 */
private suspend fun Call.await(): Response =
    suspendCancellableCoroutine { continuation ->
        enqueue(
            object : Callback {
                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    continuation.resume(response)
                }

                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    continuation.resumeWithException(e)
                }
            },
        )
        continuation.invokeOnCancellation { runCatching { cancel() } }
    }
