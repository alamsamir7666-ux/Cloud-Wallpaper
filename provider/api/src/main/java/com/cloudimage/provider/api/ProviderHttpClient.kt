package com.cloudimage.provider.api

/**
 * Immutable HTTP response handed to providers by the host.
 *
 * Non-2xx statuses arrive as regular responses — plugins decide how to
 * treat them (an empty page, a failure, a retry); only transport-level
 * problems throw. [headers] is the full multi-map; [header] gives the
 * common first-value lookup.
 */
class ProviderHttpResponse(
    val statusCode: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
) {
    /** The body decoded as UTF-8 text; empty for empty bodies. */
    val bodyText: String get() = String(body, Charsets.UTF_8)

    /** HTTP-level success: any 2xx status. */
    val isSuccessful: Boolean get() = statusCode in 200..299

    /** First value of [name], case-insensitively, or null when absent. */
    fun header(name: String): String? =
        headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            ?.firstOrNull()
}

/**
 * Raised by the host HTTP facade when a request could not be completed at
 * all — connectivity loss, timeouts, DNS failures. Providers usually let
 * this propagate: the app maps it to a typed error surface of its own.
 *
 * Open since v1.0.2 so the HOST can raise a subclass carrying its own
 * typed error across the plugin boundary; binary-compatible for plugins,
 * which only throw and catch this type, never extend it.
 */
open class ProviderHttpException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * The network facade every provider receives in
 * [WallpaperProvider.configure]. GET-only by design for V1 — every
 * wallpaper API in scope (Wallhaven, Unsplash, Pexels, Pixabay) is
 * GET-based; widen the contract in a version bump if that ever changes.
 */
interface ProviderHttpClient {
    /**
     * Performs a GET and returns the raw exchange. [headers] are appended
     * to the host's own (User-Agent is always sent and cannot be
     * overridden). Throws [ProviderHttpException] only on transport
     * failure, never for a non-2xx status.
     */
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): ProviderHttpResponse
}
