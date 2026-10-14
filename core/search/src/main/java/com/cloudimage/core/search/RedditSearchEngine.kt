package com.cloudimage.core.search

import com.cloudimage.core.model.Wallpaper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The global image search engine backed by Reddit's public listing API —
 * the first IN-APP lane of the search (v1.2.14).
 *
 * Why Reddit, and why from the device: the wallpaper subreddits
 * (`r/wallpapers`, `r/Amoledbackgrounds`, `r/EarthPorn` and friends) are
 * among the largest human-curated wallpaper repositories on the internet,
 * posted by enthusiasts who resize and tag their uploads, and Reddit
 * exposes them over a free, keyless, documented JSON interface. But that
 * interface answers residential and mobile IPs — it 403s datacenter
 * ranges outright (verified: both this project's sandbox and the z-ai
 * reader service are blocked), so the Render bridge can never host this
 * lane the way it hosts SearXNG. The phone itself is the trusted vantage
 * point: the app calls `reddit.com` directly, the user's own network
 * reputation is the credential, and no server of ours ever pays a byte.
 *
 * The contract rides on three Reddit facts:
 * - Search across subreddits is one request — `/r/a+b+c/search.json`
 *   with `restrict_sr=1` merges the subs into one relevance-ranked
 *   listing, so one call per page covers the whole scope.
 * - Pagination is CURSOR based (`after=t3_xxx` from the previous
 *   response), unlike the bridge's 1-based counter, so the engine keeps
 *   a short per-query cursor chain in memory; the sequential walks of
 *   infinite scroll are exactly the access pattern it serves.
 * - Every image post carries a `preview` block whose `source` states the
 *   ORIGINAL pixel dimensions — which makes [SearchSizeTier] an exact
 *   verification here too, the same guarantee the bridge's dimensions
 *   provide. The `resolutions` ladder gives the grid ready-made card
 *   width thumbnails, re-encoded server-side to JPEG/WebP and cropped
 *   smart — the phone never pays original bytes for a 640-px card.
 *
 * The lane is polite by design: one request at a time behind a pacing
 * mutex (Reddit's unauthenticated budget is ~10 requests/minute), a
 * descriptive User-Agent, `include_over_18=off` on the request and an
 * `over_18` skip in the parser, and a 429 answered with the typed
 * [ImageSearchError.RATE_LIMITED] the UI already knows how to phrase.
 * A blocked or throttled Reddit never surfaces directly — the merged
 * engine folds this lane's failures silently into the bridge's results.
 */
class RedditSearchEngine(
    baseClient: OkHttpClient,
    private val json: Json,
    /** Tests point this at MockWebServer; production leaves it null. */
    private val overrideBaseUrl: String? = null,
    /**
     * The minimum spacing between Reddit requests — the pacing that keeps
     * a barren-page walk inside Reddit's unauthenticated budget. Injectable
     * so tests run with zero spacing instead of sleeping.
     */
    private val minRequestIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
) : ImageSearchEngine {
    override val name: String = "Reddit"

    /**
     * Reddit is a direct-image host answering in seconds, not a slow
     * upstream — the app-wide client's long bridge timeouts would only
     * leave a dead connection hanging.
     */
    private val client =
        baseClient
            .newBuilder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
            .build()

    /** One pacing lock — requests serialize behind it with spacing. */
    private val paceMutex = Mutex()

    @Volatile
    private var lastRequestAtMs = 0L

    /**
     * The cursor the NEXT page of each active query rides — the value of
     * `after` in the last completed response for that query. Sequential
     * infinite scroll is the only access pattern, so the latest stored
     * cursor is always the right one for the page being asked.
     */
    private val nextCursor = ConcurrentHashMap<String, String>()

    override suspend fun search(
        query: String,
        page: Int,
        filters: GlobalSearchFilters,
    ): ImageSearchResult =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) {
                return@withContext ImageSearchResult.Failure(
                    error = ImageSearchError.BAD_RESPONSE,
                    detail = "Query is required",
                )
            }
            val cursor =
                if (page <= 1) {
                    null
                } else {
                    nextCursor[keyOf(query, filters)]
                }
            // A page past the end of the feed (or a query whose chain is
            // forgotten) has no cursor to ride: re-querying would answer
            // page one again, which the view model would dedup away while
            // `hasMore` spins it into a barren loop. The honest answer is
            // an ended feed.
            if (page > 1 && cursor == null) {
                return@withContext ImageSearchResult.Success(
                    page = ImageSearchPage(results = emptyList(), hasMore = false, page = page),
                )
            }
            val effectiveQuery =
                filters.sizeTier.queryToken()?.let { "$query $it" } ?: query
            val request =
                Request
                    .Builder()
                    .url(searchUrl(effectiveQuery, cursor))
                    .header(HEADER_USER_AGENT, USER_AGENT)
                    .build()

            pace()
            val response =
                try {
                    client.newCall(request).execute()
                } catch (e: SocketTimeoutException) {
                    return@withContext ImageSearchResult.Failure(
                        error = ImageSearchError.TIMEOUT,
                        detail = e.message,
                    )
                } catch (e: IOException) {
                    return@withContext ImageSearchResult.Failure(
                        error = ImageSearchError.NETWORK,
                        detail = e.message,
                    )
                }

            response.use { answered ->
                val body = answered.body?.string().orEmpty()
                when {
                    answered.code == HTTP_TOO_MANY_REQUESTS ->
                        ImageSearchResult.Failure(error = ImageSearchError.RATE_LIMITED)

                    !answered.isSuccessful ->
                        // A datacenter-blocked network answers 403 here; the
                        // merged engine folds it away, and a device on a
                        // normal network never sees it at all.
                        ImageSearchResult.Failure(
                            error = ImageSearchError.SERVER,
                            detail = "HTTP ${answered.code}",
                        )

                    else -> parse(body, page, filters, query)
                }
            }
        }

    /**
     * Serializes Reddit calls with the pacing interval — the stampede of a
     * barren-page walk (the view model's sequential fetch loop) must not
     * arrive as a burst, or Reddit answers it with a 429 the user reads
     * as a dead lane.
     */
    private suspend fun pace() {
        paceMutex.withLock {
            val now = System.currentTimeMillis()
            val wait = minRequestIntervalMs - (now - lastRequestAtMs)
            if (wait > 0) delay(wait)
            lastRequestAtMs = System.currentTimeMillis()
        }
    }

    private fun parse(
        body: String,
        page: Int,
        filters: GlobalSearchFilters,
        query: String,
    ): ImageSearchResult {
        val dto =
            try {
                json.decodeFromString(ListingDto.serializer(), body)
            } catch (e: SerializationException) {
                return ImageSearchResult.Failure(
                    error = ImageSearchError.BAD_RESPONSE,
                    detail = e.message,
                )
            }
        val key = keyOf(query, filters)
        val after = dto.data.after
        if (after.isNullOrEmpty()) {
            nextCursor.remove(key)
        } else {
            nextCursor[key] = after
            trimCursorChain()
        }
        val results =
            dto.data.children
                .asSequence()
                .map { it.data }
                .filter { it.isImagePost() }
                .filter { !it.over18 }
                .mapNotNull { it.toWallpaper() }
                .distinctBy { it.id }
                .filter { filters.sizeTier.matches(it.width, it.height) }
                .toList()
        return ImageSearchResult.Success(
            page =
                ImageSearchPage(
                    results = results,
                    hasMore = !after.isNullOrEmpty(),
                    page = page,
                ),
        )
    }

    /**
     * The URL of one subreddit-scoped search page. Built as a plain string
     * on purpose: the multi-subreddit path segment (`a+b+c`) must stay
     * literal, and query values are form-encoded — `URLEncoder` gives
     * Reddit exactly the encoding its own web client sends.
     */
    internal fun searchUrl(
        query: String,
        cursor: String?,
    ): String {
        val base = (overrideBaseUrl ?: DEFAULT_BASE_URL).trimEnd('/')
        val encoded = URLEncoder.encode(query, "UTF-8")
        var url =
            "$base/r/$SUBREDDITS/search.json" +
                "?q=$encoded&restrict_sr=1&sort=relevance&t=all" +
                "&include_over_18=off&limit=$LIMIT"
        if (cursor != null) {
            url += "&after=${URLEncoder.encode(cursor, "UTF-8")}"
        }
        return url
    }

    /** The cursor-chain key: a query under one filter set is one walk. */
    private fun keyOf(
        query: String,
        filters: GlobalSearchFilters,
    ): String = "$query|${filters.sizeTier}|${filters.safeSearch}"

    /** Keeps the cursor chain bounded — a stale query's cursor is noise. */
    private fun trimCursorChain() {
        // ConcurrentHashMap has no order to trim by; the bound is a guard
        // against pathological growth, not an LRU, and four active queries
        // is already more than one screen ever holds.
        if (nextCursor.size > MAX_TRACKED_QUERIES) {
            nextCursor.clear()
        }
    }

    /**
     * Whether a post is an image this lane can serve: it must carry a
     * usable preview (the dimensions contract), and the image signal is
     * Reddit's own — a `post_hint` of "image" or the media domain flag.
     * Text posts, link posts to galleries and videos fall through, and
     * `preview.enabled` false (rare, opted-out) takes the post with it.
     */
    private fun PostDto.isImagePost(): Boolean {
        if (postHint != IMAGE_POST_HINT && !isRedditMediaDomain) return false
        val source = preview?.images?.firstOrNull()?.source ?: return false
        return preview.enabled && source.url.isNotBlank() && source.width > 0 && source.height > 0
    }

    /**
     * One post into a [Wallpaper]. The ORIGINAL is the post's own `url`
     * when it points at a direct image (`i.redd.it`, or any image
     * extension — crossposts from `i.imgur.com` arrive this way); the
     * preview's `source` URL otherwise — which Reddit serves at the
     * source's exact dimensions, so it is a faithful original, not a
     * render. Preview URLs arrive HTML-escaped (`&amp;`) and must be
     * unescaped before the app can fetch them.
     */
    private fun PostDto.toWallpaper(): Wallpaper? {
        val source = preview?.images?.firstOrNull()?.source ?: return null
        val original =
            when {
                url.startsWith(REDDIT_IMAGE_HOST) -> url
                IMAGE_EXTENSION.containsMatchIn(url) -> url
                else -> unescape(source.url)
            }
        if (original.isBlank() || !original.startsWith("http")) return null
        val thumb =
            preview
                ?.images
                ?.firstOrNull()
                ?.resolutions
                ?.filter { it.width in 1..THUMB_WIDTH }
                ?.maxByOrNull { it.width }
                ?.url
                ?.let(::unescape)
                ?: unescape(source.url)
        return Wallpaper(
            id = "${BrowserSearchEngine.ID_PREFIX}$original",
            providerId = GLOBAL_SEARCH_PROVIDER_ID,
            thumbUrl = thumb,
            fullUrl = original,
            // The provenance line the grid and share sheet show: the
            // community the wallpaper came from, in Reddit's own idiom.
            title = subreddit.takeIf { it.isNotBlank() }?.let { "r/$it" },
            width = source.width,
            height = source.height,
            sourceUrl = permalink?.let { "https://www.reddit.com$it" },
        )
    }

    /** Reddit escapes `&` (and friends) in preview URLs; the app needs the real URL. */
    private fun unescape(url: String): String = url.replace("&amp;", "&")

    @Serializable
    private data class ListingDto(
        val data: ListingDataDto,
    )

    @Serializable
    private data class ListingDataDto(
        val after: String? = null,
        val children: List<ChildDto> = emptyList(),
    )

    @Serializable
    private data class ChildDto(
        val data: PostDto,
    )

    @Serializable
    private data class PostDto(
        val title: String = "",
        val subreddit: String = "",
        val permalink: String? = null,
        val url: String = "",
        @SerialName("post_hint") val postHint: String? = null,
        @SerialName("is_reddit_media_domain") val isRedditMediaDomain: Boolean = false,
        @SerialName("over_18") val over18: Boolean = false,
        val preview: PreviewDto? = null,
    )

    @Serializable
    private data class PreviewDto(
        val enabled: Boolean = false,
        val images: List<PreviewImageDto> = emptyList(),
    )

    @Serializable
    private data class PreviewImageDto(
        val source: PreviewVariantDto? = null,
        val resolutions: List<PreviewVariantDto> = emptyList(),
    )

    @Serializable
    private data class PreviewVariantDto(
        val url: String = "",
        val width: Int = 0,
        val height: Int = 0,
    )

    companion object {
        /**
         * The scope searched — the wallpaper communities, whose content is
         * on-topic by construction. One merged request covers them all;
         * extending the lane later is editing one constant.
         */
        internal const val SUBREDDITS =
            "wallpapers+wallpaper+MinimalWallpaper+Amoledbackgrounds+EarthPorn+MobileWallpapers"

        private const val DEFAULT_BASE_URL = "https://www.reddit.com"

        /** Reddit requires a descriptive agent; a generic one is throttled. */
        internal const val USER_AGENT =
            "Cloudimage/1.2 Android wallpaper search (+https://github.com/alamsamir7666-ux/Cloud-Wallpaper)"

        private const val HEADER_USER_AGENT = "User-Agent"

        /** Reddit's own marker for image posts. */
        private const val IMAGE_POST_HINT = "image"

        /** Reddit's direct image host — original bytes live here. */
        private const val REDDIT_IMAGE_HOST = "https://i.redd.it"

        /** Crossposted originals from other image CDNs. */
        private val IMAGE_EXTENSION = Regex("""\.(jpe?g|png|webp|gif)(\?|$)""", RegexOption.IGNORE_CASE)

        /** The card-width ceiling for thumbnails — matches the bridge lane's. */
        private const val THUMB_WIDTH = 640

        /** One page of the listing — Reddit's hard maximum. */
        private const val LIMIT = 100

        private const val HTTP_TOO_MANY_REQUESTS = 429

        /**
         * The pacing interval — a request every six seconds is the
         * unauthenticated budget's comfortable pace, and the merged
         * engine's page cadence never needs faster.
         */
        internal const val DEFAULT_MIN_INTERVAL_MS = 6_000L

        /** How many query chains the cursor map may remember. */
        private const val MAX_TRACKED_QUERIES = 4

        private const val CONNECT_TIMEOUT_S = 10L
        private const val READ_TIMEOUT_S = 15L
        private const val CALL_TIMEOUT_S = 20L
    }
}
