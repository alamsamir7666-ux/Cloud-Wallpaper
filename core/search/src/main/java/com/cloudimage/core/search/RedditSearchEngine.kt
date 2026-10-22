package com.cloudimage.core.search

import com.cloudimage.core.model.Wallpaper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
 * The global image search engine backed by Reddit's public JSON listings —
 * the in-app lane of the search, riding NO credential of any kind (v1.3.3):
 * no developer registration, no API key, no client id, no token endpoint,
 * no OAuth host. The phone asks Reddit's own front doors exactly the way
 * its browser does, and Reddit answers the way it answers any other
 * signed-out visitor.
 *
 * Why keyless, and why it works: the wallpaper subreddits
 * (`r/wallpapers`, `r/Amoledbackgrounds`, `r/EarthPorn` and friends) are
 * among the largest human-curated wallpaper repositories on the internet,
 * and every page of them is served as free JSON to anyone who asks — the
 * same documents reddit.com ships to a signed-out browser. The v1.2.25
 * installed-app OAuth identity bought its own request budget with a
 * developer-registered client id, but that id was never registered — the
 * lane has run anonymous since the day it shipped, which is exactly why
 * it never surfaced a single result: it identified itself with a
 * descriptive bot agent (`Cloudimage/1.2 …`) that Reddit's edge throttles
 * hardest, from one front door, against a per-IP anonymous budget that
 * shared carrier addresses drain by themselves. This engine keeps the
 * anonymous lane and makes it SURVIVE instead of credentialing it:
 *
 * - **The browser identity.** The request carries a stock Android Chrome
 *   user agent and an `Accept-Language`, because that is the identity
 *   Reddit's own signed-out mobile web rides — the one its edge is
 *   provisioned to serve at web scale without asking who is asking. A
 *   made-up agent names a scraper; a stock one names every phone on the
 *   train. (Verified from this project's sandbox: all Reddit front doors
 *   403 datacenter ranges by IP alone, in 30 ms, before any header is
 *   read — identity cannot rescue a blocked vantage, which is precisely
 *   why the lane runs from the device and never from the bridge.)
 *
 * - **The front-door ladder.** `www.reddit.com`, `old.reddit.com` and
 *   `api.reddit.com` serve the identical `.json` route grammar and none
 *   of them asks for a credential. A refusal that belongs to one edge —
 *   a 403, a throttle, a reset — is often not shared by the next, so one
 *   logical request walks the doors in order until one answers, and the
 *   door that last answered is where the next request starts. A code
 *   that belongs to the request rather than the door (a 404, a 400)
 *   fails immediately without touring the ladder.
 *
 * - **The whole-site fallback.** The wallpaper subs are searched first
 *   (`restrict_sr=1` — on-topic quality, curated wallpapers), but they
 *   only cover what their communities post. When their answer to a
 *   fresh query holds not a single image post — the query the user
 *   typed simply is not a wallpaper-subject there — the engine asks
 *   Reddit's site-wide search the same query once. That is how this lane
 *   answers ANY query, not just wallpaper-speak: the scope verdict is
 *   taken from the subs' own answer (an empty-of-images listing), never
 *   from a size filter (a tiered search whose every image is too small
 *   keeps walking the subs' own chain — the query is covered, the tier
 *   is the user's choice). A query that went site-wide remembers it, so
 *   deeper pages continue the site-wide cursor chain directly.
 *
 * The contract rides on three Reddit facts, unchanged from the lane's
 * first day:
 * - Search across subreddits is one request — `/r/a+b+c/search.json`
 *   with `restrict_sr=1` merges the subs into one relevance-ranked
 *   listing, so one call per page covers the whole scope.
 * - Pagination is CURSOR based (`after=t3_xxx` from the previous
 *   response), so the engine keeps a short per-query cursor chain in
 *   memory; the sequential walks of infinite scroll are exactly the
 *   access pattern it serves.
 * - Every image post carries a `preview` block whose `source` states the
 *   ORIGINAL pixel dimensions — which makes [SearchSizeTier] an exact
 *   verification here too. The `resolutions` ladder gives the grid
 *   ready-made card-width thumbnails, re-encoded server-side — the
 *   phone never pays original bytes for a 640-px card.
 *
 * The lane is polite by design: one request at a time behind a pacing
 * mutex (the anonymous budget is ~10 requests/minute per address, and
 * the merged engine's page cadence never needs faster), `raw_json=1` so
 * preview URLs arrive unescaped, `include_over_18=off` on the request
 * AND an `over_18` skip in the parser, and a 429 answered with the typed
 * [ImageSearchError.RATE_LIMITED] the UI already knows how to phrase. A
 * blocked or throttled Reddit never surfaces directly — the merged
 * engine folds this lane's failures silently into the bridge's results.
 */
class RedditSearchEngine(
    baseClient: OkHttpClient,
    private val json: Json,
    /** Tests point this at MockWebServer(s); production leaves it null. */
    private val overrideHosts: List<String>? = null,
    /**
     * The minimum spacing between one logical search request and the
     * next — the pacing that keeps a barren-page walk inside Reddit's
     * anonymous budget. Injectable so tests run with zero spacing
     * instead of sleeping.
     */
    private val minRequestIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    /** The gap before the ladder asks the next front door; tests zero it. */
    private val ladderGapMs: Long = LADDER_GAP_MS,
    /** The gap before the site-wide fallback's single ask; tests zero it. */
    private val fallbackGapMs: Long = FALLBACK_GAP_MS,
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
     * The front door the next request tries first — the index of the
     * door that last answered, within whatever [hosts] returns. A door
     * that just served is the door most likely to serve again; a door
     * that just refused is only re-asked after every other door has.
     */
    @Volatile
    private var preferredHostIndex = 0

    /**
     * The walk state of each active query: the cursor its NEXT page
     * rides (`after` from the last completed response) and whether it
     * runs site-wide (the subs held no image for this query). Sequential
     * infinite scroll is the only access pattern, so the latest stored
     * chain is always the right one for the page being asked.
     */
    private val chains = ConcurrentHashMap<String, QueryChain>()

    private data class QueryChain(
        val nextCursor: String,
        val wide: Boolean,
    )

    /** One parsed listing: the raw image posts, pre-tier — the tier is the caller's choice. */
    private data class ParsedListing(
        val after: String?,
        val imagePosts: List<Wallpaper>,
    )

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
            val key = keyOf(query, filters)
            val chain = chains[key]
            val cursor =
                if (page <= 1) {
                    null
                } else {
                    chain?.nextCursor
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
            val wide = chain?.wide ?: false
            val effectiveQuery =
                filters.sizeTier.queryToken()?.let { "$query $it" } ?: query

            pace(minRequestIntervalMs)
            val first = ladderGet { host -> searchUrl(effectiveQuery, cursor, wide, host) }
            if (first is LadderOutcome.Refused) {
                return@withContext first.failure
            }
            val listing =
                try {
                    parseListing((first as LadderOutcome.Body).body)
                } catch (e: SerializationException) {
                    return@withContext ImageSearchResult.Failure(
                        error = ImageSearchError.BAD_RESPONSE,
                        detail = e.message,
                    )
                }

            // The scope verdict: a fresh query whose wallpaper-subs answer
            // holds not one image post is not a wallpaper-subs subject —
            // ask the whole site, once, on this page. Deeper pages ride
            // whichever chain this page leaves behind. A listing whose
            // images exist but fail the size tier does NOT trigger this:
            // the subs cover the query, the tier is the user's own filter.
            if (page == 1 && !wide && listing.imagePosts.isEmpty()) {
                pace(fallbackGapMs)
                val siteWide = ladderGet { host -> searchUrl(effectiveQuery, null, true, host) }
                if (siteWide is LadderOutcome.Body) {
                    val parsed =
                        try {
                            parseListing(siteWide.body)
                        } catch (_: SerializationException) {
                            null
                        }
                    if (parsed != null) {
                        storeChain(key, parsed.after, wide = true)
                        val siteResults =
                            parsed.imagePosts.filter {
                                filters.sizeTier.matches(it.width, it.height)
                            }
                        return@withContext ImageSearchResult.Success(
                            page =
                                ImageSearchPage(
                                    results = siteResults,
                                    hasMore = !parsed.after.isNullOrEmpty(),
                                    page = page,
                                ),
                        )
                    }
                }
                // The whole-site ask failed its ladder or was unparseable:
                // the subs' own honest page stands, exactly as parsed.
            }

            storeChain(key, listing.after, wide)
            ImageSearchResult.Success(
                page =
                    ImageSearchPage(
                        results = listing.imagePosts.filter { filters.sizeTier.matches(it.width, it.height) },
                        hasMore = !listing.after.isNullOrEmpty(),
                        page = page,
                    ),
            )
        }

    /**
     * Serializes Reddit calls with spacing — the stampede of a
     * barren-page walk (the view model's sequential fetch loop) must not
     * arrive as a burst, or Reddit answers it with a 429 the user reads
     * as a dead lane. The interval is the caller's: logical page
     * requests pay the full pacing, the intra-page retries (the ladder's
     * next door, the site-wide fallback's one ask) pay a short gap — two
     * requests inside one page is still half the anonymous budget.
     */
    private suspend fun pace(intervalMs: Long) {
        paceMutex.withLock {
            val now = System.currentTimeMillis()
            val wait = intervalMs - (now - lastRequestAtMs)
            if (wait > 0) delay(wait)
            lastRequestAtMs = System.currentTimeMillis()
        }
    }

    /** The outcome of one logical request after its walk down the ladder. */
    private sealed interface LadderOutcome {
        /** A door answered 2xx; [body] is the raw listing JSON. */
        data class Body(
            val body: String,
        ) : LadderOutcome

        /** Every door refused; [failure] is the typed taxonomy verdict. */
        data class Refused(
            val failure: ImageSearchResult.Failure,
        ) : LadderOutcome
    }

    /** One door's answer to one attempt. */
    private sealed interface Attempt {
        data class Answered(
            val code: Int,
            /** The body when the door answered 2xx; null otherwise. */
            val body: String?,
        ) : Attempt

        /** The door timed out — a transport refusal like any other. */
        object TimedOut : Attempt

        /** The door was never reached: reset, DNS, refused. */
        object Unreached : Attempt
    }

    /**
     * Walks the front doors with one request each until one answers 2xx.
     * Refusals that belong to a door (403, 429, 5xx, timeouts, resets)
     * advance the walk; codes that belong to the request itself (404,
     * 400 — the path, the grammar) end it immediately, because the next
     * door would answer them identically. The door that answers becomes
     * the sticky start of the next walk.
     */
    private suspend fun ladderGet(buildUrl: (String) -> String): LadderOutcome {
        val doors = hosts()
        var sawRateLimit = false
        var sawTimeout = false
        var sawUnreached = false
        var answeredCode: Int? = null
        for (offset in doors.indices) {
            // The merged engine bounds this lane; honour that bound
            // between attempts instead of touring doors for a page that
            // has already moved on.
            currentCoroutineContext().ensureActive()
            // The sticky door is where the walk STARTS — the rotation is
            // this one index computation, nowhere else.
            val start = preferredHostIndex.coerceIn(0, doors.size - 1)
            val index = (start + offset) % doors.size
            val door = doors[index]
            if (offset > 0) pace(ladderGapMs)
            val request =
                Request
                    .Builder()
                    .url(buildUrl(door))
                    .header(HEADER_USER_AGENT, USER_AGENT)
                    .header(HEADER_ACCEPT_LANGUAGE, ACCEPT_LANGUAGE)
                    .build()
            val attempt = attemptOnce(request)
            when (attempt) {
                is Attempt.Answered -> {
                    val body = attempt.body
                    if (body != null) {
                        preferredHostIndex = index
                        return LadderOutcome.Body(body)
                    }
                    val code = attempt.code
                    if (code == HTTP_TOO_MANY_REQUESTS) {
                        sawRateLimit = true
                        answeredCode = code
                    } else if (code.isDoorLevelRefusal()) {
                        answeredCode = code
                    } else {
                        // A request-level code: no door will answer it
                        // differently.
                        return LadderOutcome.Refused(
                            ImageSearchResult.Failure(
                                error = ImageSearchError.SERVER,
                                detail = "HTTP $code",
                            ),
                        )
                    }
                }

                Attempt.TimedOut -> sawTimeout = true

                Attempt.Unreached -> sawUnreached = true
            }
        }
        val failure =
            when {
                sawRateLimit ->
                    ImageSearchResult.Failure(error = ImageSearchError.RATE_LIMITED)

                answeredCode != null ->
                    ImageSearchResult.Failure(
                        error = ImageSearchError.SERVER,
                        detail = "HTTP $answeredCode",
                    )

                sawTimeout -> ImageSearchResult.Failure(error = ImageSearchError.TIMEOUT)

                else ->
                    ImageSearchResult.Failure(
                        error = ImageSearchError.NETWORK,
                        detail = if (sawUnreached) "no Reddit front door was reachable" else null,
                    )
            }
        return LadderOutcome.Refused(failure)
    }

    /** One door, one attempt — typed, never throwing. */
    private fun attemptOnce(request: Request): Attempt =
        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Attempt.Answered(code = response.code, body = response.body?.string().orEmpty())
                } else {
                    Attempt.Answered(code = response.code, body = null)
                }
            }
        } catch (_: SocketTimeoutException) {
            Attempt.TimedOut
        } catch (_: IOException) {
            Attempt.Unreached
        }

    /**
     * Whether an HTTP refusal belongs to the DOOR (worth asking another
     * one) rather than to the request (every door would repeat it): the
     * block family and the server-error family are edge-local; the rest
     * is grammar.
     */
    private fun Int.isDoorLevelRefusal(): Boolean = this == 401 || this == 403 || this in 500..504

    /** The doors of the next walk: the test override, or Reddit's own front doors in their fixed order. */
    private fun hosts(): List<String> = overrideHosts ?: REAL_HOSTS

    /**
     * Remembers the walk this page leaves behind: the cursor its next
     * page rides, and the scope it runs in. A null cursor ends the chain
     * outright — the feed is over, and a re-ask of the query starts a
     * fresh verdict from the subs.
     */
    private fun storeChain(
        key: String,
        after: String?,
        wide: Boolean,
    ) {
        if (after.isNullOrEmpty()) {
            chains.remove(key)
        } else {
            chains[key] = QueryChain(nextCursor = after, wide = wide)
            trimChains()
        }
    }

    private fun parseListing(body: String): ParsedListing {
        val dto = json.decodeFromString(ListingDto.serializer(), body)
        val posts =
            dto.data.children
                .asSequence()
                .map { it.data }
                .filter { it.isImagePost() }
                .filter { !it.over18 }
                .mapNotNull { it.toWallpaper() }
                .distinctBy { it.id }
                .toList()
        return ParsedListing(after = dto.data.after, imagePosts = posts)
    }

    /**
     * The URL of one search page. Built as a plain string on purpose:
     * the multi-subreddit path segment (`a+b+c`) must stay literal, and
     * query values are form-encoded — `URLEncoder` gives Reddit exactly
     * the encoding its own web client sends. [wide] picks the scope:
     * the subs' merged search, or the site-wide one.
     */
    internal fun searchUrl(
        query: String,
        cursor: String?,
        wide: Boolean,
        host: String,
    ): String {
        val base = host.trimEnd('/')
        val encoded = URLEncoder.encode(query, "UTF-8")
        val scope = if (wide) "/search.json" else "/r/$SUBREDDITS/search.json"
        var url =
            "$base$scope?q=$encoded" +
                (if (wide) "" else "&restrict_sr=1") +
                "&sort=relevance&t=all&include_over_18=off&limit=$LIMIT&raw_json=1"
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

    /** Keeps the chain set bounded — a stale query's chain is noise. */
    private fun trimChains() {
        // ConcurrentHashMap has no order to trim by; the bound is a guard
        // against pathological growth, not an LRU, and four active queries
        // is already more than one screen ever holds.
        if (chains.size > MAX_TRACKED_QUERIES) {
            chains.clear()
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
     * render. Preview URLs may arrive HTML-escaped (`&amp;`) when
     * `raw_json` is not honored; the unescape is idempotent either way.
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
         * The scope searched first — the wallpaper communities, whose
         * content is on-topic by construction. One merged request covers
         * them all; extending the lane later is editing one constant.
         */
        internal const val SUBREDDITS =
            "wallpapers+wallpaper+MinimalWallpaper+Amoledbackgrounds+EarthPorn+MobileWallpapers"

        /**
         * Reddit's three public front doors — the same router behind
         * each, the same `.json` grammar on all, no credential asked by
         * any. A refusal that lives on one edge is often not shared by
         * the next, so a search walks them until one answers.
         */
        private val REAL_HOSTS =
            listOf(
                "https://www.reddit.com",
                "https://old.reddit.com",
                "https://api.reddit.com",
            )

        /**
         * The stock signed-out Android Chrome identity — the exact
         * agent shape Reddit's own mobile web rides, which is the one
         * its edge serves at web scale without asking who is asking.
         * (The v1.2.x lane's descriptive `Cloudimage/1.2 …` agent named
         * a script, and anonymous Reddit treats named scripts as
         * second-class traffic on drained addresses.)
         */
        internal const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

        /** Every browser speaks a language; so does this lane. */
        private const val HEADER_USER_AGENT = "User-Agent"

        private const val HEADER_ACCEPT_LANGUAGE = "Accept-Language"

        private const val ACCEPT_LANGUAGE = "en-US,en;q=0.9"

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
         * The pacing interval between logical page requests — one
         * request every six seconds sits well inside the ~10/minute
         * anonymous budget, and the merged engine's page cadence never
         * needs faster.
         */
        internal const val DEFAULT_MIN_INTERVAL_MS = 6_000L

        /** The gap before the ladder asks the next front door. */
        private const val LADDER_GAP_MS = 750L

        /** The gap before the site-wide fallback's single ask. */
        private const val FALLBACK_GAP_MS = 1_500L

        /** How many query chains the state map may remember. */
        private const val MAX_TRACKED_QUERIES = 4

        private const val CONNECT_TIMEOUT_S = 10L
        private const val READ_TIMEOUT_S = 15L
        private const val CALL_TIMEOUT_S = 20L
    }
}
