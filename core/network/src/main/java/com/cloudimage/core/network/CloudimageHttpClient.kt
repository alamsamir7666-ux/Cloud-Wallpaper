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
 * The single HTTP entry point every provider goes through — built-in providers
 * in-process, and (from Part 5) loaded extensions via injected client sharing.
 *
 * Responsibilities:
 * - identify the app to servers with a stable [User-Agent][USER_AGENT],
 * - turn every failure mode into a typed [NetworkResult], never an exception,
 * - parse JSON bodies through the shared, forgiving [Json] configuration.
 */
@Singleton
class CloudimageHttpClient
    @Inject
    constructor(
        private val okHttpClient: OkHttpClient,
        private val json: Json,
    ) {
        /** Performs a GET and returns the raw body. */
        suspend fun get(url: String): NetworkResult<String> =
            withContext(Dispatchers.IO) {
                val request =
                    Request.Builder()
                        .url(url)
                        .header(HEADER_USER_AGENT, USER_AGENT)
                        .build()
                try {
                    okHttpClient.newCall(request).await().use { response ->
                        if (response.isSuccessful) {
                            Success(response.body?.string().orEmpty())
                        } else {
                            Failure(NetworkError.Http(response.code, url))
                        }
                    }
                } catch (e: SocketTimeoutException) {
                    Failure(NetworkError.Timeout)
                } catch (e: IOException) {
                    Failure(NetworkError.Io(e))
                }
            }

        /**
         * Performs a GET and returns the raw body bytes — for downloads of
         * full-resolution images (apply-as-wallpaper, save-to-gallery, share).
         *
         * Same failure taxonomy as [get]; the body is buffered in memory, which
         * is fine for wallpaper-sized files (single-digit megabytes).
         */
        suspend fun download(url: String): NetworkResult<ByteArray> =
            withContext(Dispatchers.IO) {
                val request =
                    Request.Builder()
                        .url(url)
                        .header(HEADER_USER_AGENT, USER_AGENT)
                        .build()
                try {
                    okHttpClient.newCall(request).await().use { response ->
                        if (response.isSuccessful) {
                            Success(response.body?.bytes() ?: ByteArray(0))
                        } else {
                            Failure(NetworkError.Http(response.code, url))
                        }
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

            /** Sent with every request; some public APIs require identification. */
            internal const val USER_AGENT =
                "Cloudimage/0.1 (Android; +https://github.com/alamsamir7666-ux/Cloud-Wallpaper)"
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
