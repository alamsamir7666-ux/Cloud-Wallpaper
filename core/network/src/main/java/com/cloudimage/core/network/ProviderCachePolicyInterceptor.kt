package com.cloudimage.core.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * The client-side freshness policy for provider API documents (v1.3.1):
 * when a successful GET answers in a text-bearing content type (the HTML
 * and JSON documents every extension parses) and the origin said NOTHING
 * about freshness — no `Cache-Control`, no `Expires` — the response is
 * rewritten to carry `Cache-Control: public, max-age=60`.
 *
 * Wallpaper sources overwhelmingly send documents with no cache headers
 * at all: wallhaven's API, zedge's HTML, the GraphQL JSON every
 * continuation posts for. Without this rewrite OkHttp's disk cache (which
 * this release installs on the shared client) would store nothing and
 * learn nothing — the cache only bites when someone tells it a document
 * may age. Sixty seconds is chosen for a browsing surface: invisible to
 * a user paging a grid (they never page back inside a minute and expect
 * brand-new documents), yet it turns every repeat visit, every
 * back-navigation, every cold-start home screen inside that window from
 * a full network round-trip into a disk read — and makes the
 * stale-on-error seam ([CloudimageHttpClient.getCachedOnly]) possible,
 * which is what keeps a source's grid readable when the network drops.
 *
 * Deliberately narrow, in both directions:
 *
 * - Only 2xx GETs. An error page or challenge body is never made
 *   cacheable — a stored 403 replayed as the "offline answer" would be a
 *   lie the source never told. POST responses never even reach OkHttp's
 *   cache (it stores GETs only), so this rewrite can't touch the v1.3
 *   POST lane's answers at all.
 * - Only text-bearing documents (text of any kind, JSON, XML). Images
 *   are excluded on purpose: they belong to the Coil pipeline's own disk
 *   cache — the app's image client deliberately drops the shared HTTP
 *   cache (`newImageLoader` builds `.cache(null)`) so no byte is stored
 *   twice, and this interceptor keeps that separation honest by never
 *   granting image responses the HTTP-cache freshness they would need
 *   to be stored a second time.
 * - Only when the origin is SILENT. `no-store`, `private`, `max-age`
 *   of any kind — anything the server actually said wins, untouched.
 *   This is a default for speechless responses, not an override for
 *   opinions.
 *
 * Installed as a NETWORK interceptor, not an application interceptor:
 * the cache stores what the network chain produces, and it must see
 * this header BEFORE the write. (An application interceptor runs after
 * the cache and would only be decorating the copy in flight.)
 */
object ProviderCachePolicyInterceptor : Interceptor {
    /** The freshness granted to otherwise-uncacheable documents. */
    const val MAX_AGE_SECONDS = 60

    private val DOCUMENT_PREFIXES =
        listOf(
            "text/",
            "application/json",
            "application/xml",
            "application/javascript",
        )

    /** Content types that advertise parameters (`; charset=utf-8`) still match on the bare type. */
    private fun Response.isTextDocument(): Boolean {
        val contentType = header("Content-Type") ?: return false
        val bare = contentType.substringBefore(';').trim().lowercase()
        return DOCUMENT_PREFIXES.any { prefix -> bare.startsWith(prefix) }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (chain.request().method != "GET") return response
        if (!response.isSuccessful || !response.isTextDocument()) return response
        if (response.header("Cache-Control") != null || response.header("Expires") != null) return response
        return response
            .newBuilder()
            .header("Cache-Control", "public, max-age=$MAX_AGE_SECONDS")
            .build()
    }
}
