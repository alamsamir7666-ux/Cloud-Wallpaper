package com.cloudimage.core.search

import com.cloudimage.core.model.Wallpaper
import com.cloudimage.core.network.CloudimageHttpClient
import com.cloudimage.core.network.NetworkError
import com.cloudimage.core.network.NetworkResult
import com.cloudimage.core.search.ImageSearchResult.Failure
import com.cloudimage.core.search.ImageSearchResult.Success
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import javax.inject.Singleton

/**
 * The keyless global image engine (v1.2.0): DuckDuckGo image search.
 *
 * The engine is unofficial — the honest word is "free" — and works exactly
 * like a real browser session, which is what keeps it from being blocked
 * as a bot:
 *
 * 1. the HTML search page is fetched once per query to obtain the `vqd`
 *    session token embedded in its scripts,
 * 2. the internal JSON endpoint (`i.js`) is then called with that token and
 *    the header set of a same-origin XHR.
 *
 * Tokens are cached ~12 minutes; DDG hands a fresh one back with every
 * batch, and each response banks it for the pages to come. Pagination
 * rides the `s` offset cursor (100 results per page); SafeSearch is the
 * `p` parameter; the size tier biases the pool through DDG's own `f`
 * filter and is then verified exactly on the client — the engine's
 * "Large"/"Wallpaper" buckets are fuzzy, our tier promises are not.
 *
 * Everything a transport or shape failure can produce is folded into typed
 * [Failure]s; three attempts with linear backoff (and a hard ~32s budget)
 * absorb the engine's occasional throttling hiccups.
 */
@Singleton
class DuckDuckGoImageSearchEngine(
    private val client: CloudimageHttpClient,
    private val baseUrl: String = "https://duckduckgo.com",
) : ImageSearchEngine {
    override val name: String = "DuckDuckGo"

    /** The vqd token exchange: found, or why not. */
    private sealed interface Vqd {
        data class Token(
            val value: String,
        ) : Vqd

        data class Denied(
            val error: ImageSearchError,
            val detail: String?,
        ) : Vqd
    }

    /** Query -> (token, minted-at); tokens go stale, so nothing lives forever. */
    private class VqdCache {
        private val entries = HashMap<String, Pair<String, Long>>()

        @Synchronized
        fun get(query: String): String? = entries[query]?.takeIf { System.currentTimeMillis() - it.second < TTL_MS }?.first

        @Synchronized
        fun set(
            query: String,
            token: String,
        ) {
            if (entries.size > MAX_ENTRIES) {
                entries.entries
                    .sortedBy { it.value.second }
                    .take(EVICTION_BATCH)
                    .forEach { entries.remove(it.key) }
            }
            entries[query] = token to System.currentTimeMillis()
        }

        @Synchronized
        fun remove(query: String) {
            entries.remove(query)
        }

        companion object {
            const val TTL_MS = 12 * 60 * 1000L
            const val MAX_ENTRIES = 60
            const val EVICTION_BATCH = 20
        }
    }

    private val vqdCache = VqdCache()

    override suspend fun search(
        query: String,
        page: Int,
        filters: GlobalSearchFilters,
    ): ImageSearchResult =
        withContext(Dispatchers.IO) {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) {
                return@withContext Success(ImageSearchPage(results = emptyList(), hasMore = false, page = page))
            }
            if (page < 1) {
                return@withContext Failure(ImageSearchError.BAD_RESPONSE, "page must be 1-based")
            }

            val cacheKey = trimmed.lowercase()
            val startedAt = System.currentTimeMillis()
            var lastError: Failure? = null

            for (attempt in 1..ATTEMPTS) {
                if (attempt > 1) {
                    if (System.currentTimeMillis() - startedAt > RETRY_BUDGET_MS) break
                    delay(RETRY_BACKOFF_MS * (attempt - 1))
                }

                var vqd = vqdCache.get(cacheKey)
                if (vqd == null) {
                    when (val token = fetchVqd(trimmed)) {
                        is Vqd.Token -> {
                            vqd = token.value
                            vqdCache.set(cacheKey, token.value)
                        }
                        is Vqd.Denied -> {
                            lastError = Failure(token.error, token.detail)
                            continue
                        }
                    }
                }

                when (val outcome = fetchResults(cacheKey, trimmed, page, filters, vqd)) {
                    is Success -> return@withContext outcome
                    is Failure -> {
                        // A JSON-less or malformed body means the token went
                        // stale mid-session — drop it so the next attempt (or
                        // the next search) starts a fresh one. Transport-level
                        // failures keep the token: a timeout says nothing
                        // about its validity.
                        if (outcome.error == ImageSearchError.BAD_RESPONSE ||
                            outcome.error == ImageSearchError.RATE_LIMITED
                        ) {
                            vqdCache.remove(cacheKey)
                        }
                        lastError = outcome
                    }
                }
            }

            lastError ?: Failure(ImageSearchError.BAD_RESPONSE, "the engine could not be reached")
        }

    /**
     * Step 1 of the browser flow: load the images search page like a
     * navigating browser and lift the `vqd` token out of its scripts. A 2xx
     * page without a token is DuckDuckGo's anomaly response — throttle
     * language, not a protocol the caller can fix.
     */
    private suspend fun fetchVqd(query: String): Vqd {
        val url: HttpUrl =
            baseUrl
                .toHttpUrl()
                .newBuilder()
                .apply {
                    addQueryParameter("q", query)
                    addQueryParameter("iax", "images")
                    addQueryParameter("ia", "images")
                }.build()

        return when (val response = client.get(url.toString(), HTML_HEADERS)) {
            is NetworkResult.Failure -> Vqd.Denied(mapFailure(response.error), response.error.toDetail())
            is NetworkResult.Success ->
                VQD_PATTERNS
                    .firstNotNullOfOrNull { pattern ->
                        pattern
                            .find(response.value)
                            ?.groupValues
                            ?.get(1)
                            ?.takeIf { it.isNotBlank() }
                    }?.let { Vqd.Token(it) }
                    ?: Vqd.Denied(
                        ImageSearchError.RATE_LIMITED,
                        "the engine served a page without a search session token",
                    )
        }
    }

    /**
     * Step 2 of the browser flow: the internal JSON endpoint, called with
     * the token and the header set of a same-origin XHR.
     */
    private suspend fun fetchResults(
        cacheKey: String,
        query: String,
        page: Int,
        filters: GlobalSearchFilters,
        vqd: String,
    ): ImageSearchResult {
        val url: HttpUrl =
            baseUrl
                .toHttpUrl()
                .newBuilder()
                .apply {
                    addPathSegment("i.js")
                    addQueryParameter("l", "wt-wt")
                    addQueryParameter("o", "json")
                    addQueryParameter("q", query)
                    addQueryParameter("vqd", vqd)
                    addQueryParameter("f", sizeBias(filters.sizeTier))
                    addQueryParameter("p", if (filters.safeSearch) "1" else "-1")
                    if (page > 1) addQueryParameter("s", ((page - 1) * RESULTS_PER_PAGE).toString())
                }.build()

        return when (val response = client.get(url.toString(), XHR_HEADERS)) {
            is NetworkResult.Failure -> Failure(mapFailure(response.error), response.error.toDetail())
            is NetworkResult.Success -> parseResults(cacheKey, response.value, page, filters)
        }
    }

    /** Maps the engine's raw JSON body into an [ImageSearchPage]. */
    internal fun parseResults(
        cacheKey: String,
        body: String,
        page: Int,
        filters: GlobalSearchFilters,
    ): ImageSearchResult {
        val root =
            runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: return Failure(ImageSearchError.RATE_LIMITED, "the engine returned a non-JSON body")
        val rawResults: JsonArray =
            runCatching { root["results"]?.jsonArray }.getOrNull()
                ?: return Failure(
                    ImageSearchError.BAD_RESPONSE,
                    "the engine response carried no results array",
                )

        // DDG hands back a fresh token with each batch — bank it for the
        // pages to come.
        runCatching { root["vqd"]?.jsonPrimitive?.contentOrNull }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { vqdCache.set(cacheKey, it) }

        val results = ArrayList<Wallpaper>()
        val seenUrls = HashSet<String>()
        for (element in rawResults) {
            val item = element as? JsonObject ?: continue
            val full = item.string("image") ?: item.string("thumbnail") ?: continue
            val thumb = item.string("thumbnail") ?: full
            if (!seenUrls.add(full)) continue
            val width = item.int("width")
            val height = item.int("height")
            // The tier's exact client-side verification on top of the
            // engine-side bias — unknown dimensions never pass a real tier.
            if (!filters.sizeTier.matches(width, height)) continue
            results +=
                Wallpaper(
                    id = "ddg:$full",
                    providerId = GLOBAL_SEARCH_PROVIDER_ID,
                    thumbUrl = thumb,
                    fullUrl = full,
                    title = item.string("title")?.take(MAX_TITLE_LENGTH),
                    width = width,
                    height = height,
                    sourceUrl = item.string("url") ?: full,
                )
        }

        val related =
            runCatching { root["query_expansions"]?.jsonArray }
                .getOrNull()
                ?.mapNotNull { expansion ->
                    (expansion as? JsonObject)?.string("displayText")?.trim()?.takeIf { it.isNotEmpty() }
                }?.take(MAX_RELATED)
                .orEmpty()

        // hasMore from the RAW batch: an all-filtered page must not end the
        // scroll when the engine itself says there is more.
        val hasMore = root.containsKey("next") && rawResults.size > 0
        return Success(ImageSearchPage(results = results, hasMore = hasMore, page = page, relatedSearches = related))
    }

    /**
     * Maps our pixel tiers onto DDG's own size buckets — the 4th slot of
     * the `f` parameter. Biased pools mean the exact tier filter finds
     * matches on the first page instead of digging through five.
     */
    private fun sizeBias(tier: SearchSizeTier): String =
        when (tier) {
            SearchSizeTier.ANY -> ANY_BIAS
            SearchSizeTier.HD -> ",,,size:Large"
            SearchSizeTier.FHD, SearchSizeTier.QHD, SearchSizeTier.UHD -> ",,,size:Wallpaper"
        }

    private fun mapTransport(error: NetworkError): ImageSearchError =
        when (error) {
            is NetworkError.Timeout -> ImageSearchError.TIMEOUT
            is NetworkError.Io -> ImageSearchError.NETWORK
            is NetworkError.Http -> ImageSearchError.SERVER
            is NetworkError.Serialization -> ImageSearchError.BAD_RESPONSE
            is NetworkError.Source -> ImageSearchError.BAD_RESPONSE
        }

    /** The engine's own throttle statuses read as throttling, not as a server bug. */
    private fun mapFailure(error: NetworkError): ImageSearchError =
        if (error is NetworkError.Http && error.code in THROTTLED_STATUS_CODES) {
            ImageSearchError.RATE_LIMITED
        } else {
            mapTransport(error)
        }

    private fun NetworkError.toDetail(): String? =
        when (this) {
            is NetworkError.Http -> "HTTP $code"
            is NetworkError.Source -> reason
            else -> null
        }

    private fun JsonObject.string(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(key: String): Int? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull() }.getOrNull()?.takeIf { it > 0 }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        const val ATTEMPTS = 3
        const val RETRY_BACKOFF_MS = 700L
        const val RETRY_BUDGET_MS = 32_000L
        const val RESULTS_PER_PAGE = 100
        const val MAX_TITLE_LENGTH = 300
        const val MAX_RELATED = 8
        const val ANY_BIAS = ",,,"

        /** The statuses DuckDuckGo answers with while throttling a client. */
        val THROTTLED_STATUS_CODES = setOf(403, 429)

        /** The token appears in several script shapes across DDG's page variants. */
        val VQD_PATTERNS =
            listOf(
                Regex("vqd=[\"']([\\d-]+)[\"']"),
                Regex("vqd=([\\d-]+)[\"&\\s]"),
                Regex("vqd=([\\d-]+)"),
            )

        /**
         * The full browser-mimicry header sets — a real Chrome session, not
         * an app. The client's own User-Agent is overridden per request
         * (OkHttp replaces same-named headers case-insensitively).
         */
        val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        val HTML_HEADERS =
            mapOf(
                "User-Agent" to BROWSER_UA,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.9",
                "Upgrade-Insecure-Requests" to "1",
                "Sec-Fetch-Dest" to "document",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "none",
                "Sec-Fetch-User" to "?1",
            )

        val XHR_HEADERS =
            mapOf(
                "User-Agent" to BROWSER_UA,
                "Accept" to "application/json, text/plain, */*",
                "Accept-Language" to "en-US,en;q=0.9",
                "Referer" to "https://duckduckgo.com/",
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "same-origin",
                "X-Requested-With" to "XMLHttpRequest",
            )
    }
}
