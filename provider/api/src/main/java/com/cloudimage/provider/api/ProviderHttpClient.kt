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
 * [WallpaperProvider.configure]. GET-only was the V1 design — every
 * wallpaper API in scope (Wallhaven, Unsplash, Pexels, Pixabay) is
 * GET-based — and v1.3 of the API widens the contract with [post] for
 * the sources whose only addressable lane is a POST (zedge.net's
 * GraphQL continuation, whose GET the server answers 405).
 */
interface ProviderHttpClient {
    /**
     * Performs a GET and returns the raw exchange. [headers] ride on top
     * of the host's own, and a provider-supplied `User-Agent` replaces
     * the host default for that request — the sanctioned way for a
     * scraper to present a browser identity Cloudflare will serve (the
     * WallpaperFlare extension relies on it). The one exception: a
     * WebView-earned Cloudflare clearance is bound to the agent it was
     * earned with, so on replayed challenged requests the host overrides
     * the agent back — clearance binding wins over provider identity.
     * Throws [ProviderHttpException] on transport failure, never for an
     * ordinary non-2xx status — the host may additionally raise its own
     * [ProviderHttpException] subclass when the answer is Cloudflare's
     * own block or challenge page rather than the source's (the host's
     * bypass machinery already ran by then), which plugins should let
     * propagate exactly like a transport failure.
     */
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): ProviderHttpResponse

    /**
     * Performs a POST with a text [body] and returns the raw exchange —
     * the v1.3 lane for sources whose continuation is a POST (zedge.net's
     * `/api/graphql`, whose GET the server refuses with 405). The same
     * ladder rules as [get]: [headers] ride on top of the host's own, a
     * provider-supplied `User-Agent` is overridden back on challenged
     * requests when a WebView-earned clearance binds the host to its own
     * agent, non-2xx statuses flow through as ordinary responses for the
     * plugin to phrase, and only transport failures throw
     * [ProviderHttpException] (plus the host's own subclass for
     * Cloudflare furniture, exactly as [get]).
     *
     * ## Version semantics (the compatibility contract)
     *
     * DEFAULT-THROWING so this is a binary-compatible widening: hosts
     * compiled before v1.3 carry no `post` at all, and a plugin that
     * calls it there hits a `NoSuchMethodError` at the call site — which
     * is why a plugin that means to run on BOTH host generations wraps
     * the call in its own `runCatching` (catching [Throwable]) and
     * degrades to its honest pre-POST behavior on failure. A host that
     * implements this method answers POSTs through the same
     * clearance/WebView machinery as its GETs; the default body is the
     * honest refusal for hosts with no POST lane (and for JVM test
     * doubles, which inherit it untouched).
     */
    suspend fun post(
        url: String,
        body: String,
        contentType: String = "application/json",
        headers: Map<String, String> = emptyMap(),
    ): ProviderHttpResponse = throw ProviderHttpException("POST $url is not supported by this host")
}
