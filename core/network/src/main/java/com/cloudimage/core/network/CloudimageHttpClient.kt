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
 * - solve Cloudflare challenges on the app's side: a challenged request is
 *   replayed once under a WebView-earned clearance (see [cloudflare]).
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
         * A Cloudflare challenge answer no longer ends the exchange: the
         * client asks [cloudflare] for a clearance — a headless WebView runs
         * the challenge for real, the mechanism a pure-JVM plugin cannot
         * carry — and replays the request once under the earned cookies and
         * User-Agent. A clearance already on file for the host is attached
         * before the first attempt, so a solved host stays solved. When no
         * clearance can be earned, the challenge response itself is returned
         * like any other non-2xx: per the facade contract, providers decide
         * how to treat it, and their readable failures travel the app's
         * source-failure banner as before.
         */
        suspend fun getRaw(
            url: String,
            extraHeaders: Map<String, String> = emptyMap(),
        ): NetworkResult<HttpPayload> =
            withContext(Dispatchers.IO) {
                try {
                    val state = cloudflare.bypassStateFor(url)
                    val first = execute(url, extraHeaders, state)
                    if (!CloudflareChallenge.isChallenge(first)) {
                        Success(first)
                    } else {
                        val bypass = cloudflare.solve(url, staleState = state)
                        if (bypass == null) {
                            Success(first)
                        } else {
                            Success(execute(url, extraHeaders, bypass))
                        }
                    }
                } catch (e: SocketTimeoutException) {
                    Failure(NetworkError.Timeout)
                } catch (e: IOException) {
                    Failure(NetworkError.Io(e))
                }
            }

        /**
         * Runs one GET and buffers the exchange. [bypass] — the clearance a
         * request carries when the host has one on file, or a freshly earned
         * one on the replay — is applied LAST: a clearance is bound to the
         * User-Agent that earned it, so it overrides both the host agent and
         * any provider browser UA for that host, and its cookies ride the
         * same request.
         */
        private suspend fun execute(
            url: String,
            extraHeaders: Map<String, String>,
            bypass: CloudflareBypass?,
        ): HttpPayload {
            val request =
                Request
                    .Builder()
                    .url(url)
                    .header(HEADER_USER_AGENT, USER_AGENT)
                    .apply {
                        for ((name, value) in extraHeaders) {
                            header(name, value)
                        }
                        if (bypass != null) {
                            header(HEADER_USER_AGENT, bypass.userAgent)
                            header(HEADER_COOKIE, bypass.cookieHeader)
                        }
                    }.build()
            return okHttpClient.newCall(request).await().use { response ->
                HttpPayload(
                    statusCode = response.code,
                    headers = response.headers.toMultimap(),
                    body = response.body?.bytes() ?: ByteArray(0),
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
        suspend fun download(url: String): NetworkResult<ByteArray> {
            val raw = getRaw(url)
            return when (raw) {
                is Failure -> raw
                is Success ->
                    if (raw.value.isSuccessful) {
                        Success(raw.value.body)
                    } else {
                        Failure(NetworkError.Http(raw.value.statusCode, url))
                    }
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

            /** Sent with every request; some public APIs require identification. */
            internal const val USER_AGENT =
                "Cloudimage/1.0 (Android; +https://github.com/alamsamir7666-ux/Cloud-Wallpaper)"
        }
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
