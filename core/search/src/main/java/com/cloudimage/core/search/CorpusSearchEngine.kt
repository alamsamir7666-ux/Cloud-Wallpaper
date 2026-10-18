package com.cloudimage.core.search

import com.cloudimage.core.model.Wallpaper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * The global image search engine backed by the shared Supabase corpus
 * (v1.2.23) — the third lane of the merged search, after the bridge and
 * Reddit.
 *
 * The corpus is a public, seeded `search_corpus` table of wallpaper search
 * metadata (query, image URL, pixel dimensions, source host) that the
 * project harvests and that the Render bridge grows organically: every
 * query the bridge serves is mirrored into it server-side. Reading it
 * from the phone is one anonymous PostgREST GET — no key rotation
 * anxiety (the anon key is public by design and RLS-locked to SELECT),
 * no wake-up latency (unlike the Render free tier's 15-minute sleep, a
 * paused-from-idle Supabase project is the only cold start this lane can
 * meet, and the bridge pings the corpus precisely so that never happens),
 * and no per-query upstream cost at all: a covered query answers from
 * stored metadata in one round trip.
 *
 * The contract deliberately mirrors its sibling lanes: [page] is a plain
 * 1-based counter (PostgREST `offset`/`limit` windows map trivially),
 * result ids ride the SAME `gs:` + URL prefix the bridge and Reddit mint
 * so the same image found by multiple lanes collapses into one card, and
 * [ImageSearchPage.hasMore] comes from the RAW batch — a page whose rows
 * were all size-filtered must not kill infinite scroll, because the
 * bridge and Reddit lanes keep answering pages regardless.
 *
 * What this lane is NOT: a replacement for the live lanes. It is
 * coverage and resilience on top of them — the merged engine runs all
 * lanes concurrently, so an un-covered query simply contributes nothing
 * from here, and a Supabase outage (paused project, rotated key,
 * migrated ref) is invisible behind the lanes that still answer. The
 * corpus is also bounded: past [MAX_PAGES] the lane goes quiet instead
 * of paging into emptiness, leaving deep scroll to the live lanes.
 */
class CorpusSearchEngine(
    private val config: SearchBackendConfig,
    baseClient: OkHttpClient,
    private val json: Json,
    /** Tests point this at MockWebServer; production leaves it null and [config] decides. */
    private val overrideCorpusUrl: String? = null,
    /** The anon key that rides with [overrideCorpusUrl]; tests inject a fake. */
    private val overrideAnonKey: String? = null,
) : ImageSearchEngine {
    override val name: String = "Corpus"

    /**
     * Supabase answers in a few hundred milliseconds — this is the
     * fastest lane of the three, and the merged engine's secondary-budget
     * already bounds it. The app-wide client's bridge-era timeouts would
     * only leave a dead connection hanging.
     */
    private val client =
        baseClient
            .newBuilder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
            .build()

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
            if (page > MAX_PAGES) {
                // The corpus is a bounded seed, not an infinite stream —
                // past its depth the live lanes own the scroll. An honest
                // empty page (not a failure) keeps the merge healthy.
                return@withContext ImageSearchResult.Success(
                    page = ImageSearchPage(results = emptyList(), hasMore = false, page = page),
                )
            }
            val endpoint =
                overrideCorpusUrl
                    ?.let { SearchBackendConfig.CorpusEndpoint(it.trimEnd('/'), overrideAnonKey ?: "") }
                    ?: config.corpus()
            if (endpoint == null || endpoint.anonKey.isBlank()) {
                // Corpus-less build (or a stripped remote config): this
                // lane is simply absent. A typed failure folds invisibly
                // into the merge, exactly like any other dropped lane.
                return@withContext ImageSearchResult.Failure(
                    error = ImageSearchError.SERVER,
                    detail = "corpus not configured",
                )
            }

            val request = buildRequest(endpoint, query, page)
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
                when {
                    answered.code == HTTP_TOO_MANY_REQUESTS ->
                        ImageSearchResult.Failure(error = ImageSearchError.RATE_LIMITED)

                    !answered.isSuccessful ->
                        ImageSearchResult.Failure(
                            error = ImageSearchError.SERVER,
                            detail = "HTTP ${answered.code}",
                        )

                    else -> parse(answered.body?.string().orEmpty(), page, filters)
                }
            }
        }

    /**
     * One anonymous PostgREST GET: a `select` of exactly the columns the
     * [Wallpaper] mapping consumes, an exact-match filter on the
     * normalized query (spaces percent-encode to %20 — verified live
     * against the deployed PostgREST: it takes the bare `eq.` form, while
     * the double-quoted form silently matches nothing), freshest rows
     * first with a deterministic id tiebreaker, and an offset window for
     * the page counter.
     */
    internal fun buildRequest(
        endpoint: SearchBackendConfig.CorpusEndpoint,
        rawQuery: String,
        page: Int,
    ): Request {
        val norm = normalizeQuery(rawQuery)
        val url: HttpUrl =
            "${endpoint.url}/$CORPUS_PATH"
                .toHttpUrl()
                .newBuilder()
                .apply {
                    addQueryParameter("select", SELECT_COLUMNS)
                    addQueryParameter("query_norm", "eq.$norm")
                    addQueryParameter("order", "scraped_at.desc,id.desc")
                    addQueryParameter("limit", RESULTS_PER_PAGE.toString())
                    if (page > 1) addQueryParameter("offset", ((page - 1) * RESULTS_PER_PAGE).toString())
                }.build()
        return Request
            .Builder()
            .url(url)
            .header("apikey", endpoint.anonKey)
            .header("Authorization", "Bearer ${endpoint.anonKey}")
            .build()
    }

    private fun parse(
        body: String,
        page: Int,
        filters: GlobalSearchFilters,
    ): ImageSearchResult {
        val rows =
            try {
                json.decodeFromString<List<CorpusRowDto>>(body)
            } catch (e: SerializationException) {
                return ImageSearchResult.Failure(
                    error = ImageSearchError.BAD_RESPONSE,
                    detail = e.message,
                )
            }
        val results =
            rows
                .asSequence()
                .mapNotNull { it.toWallpaper() }
                .filter { filters.sizeTier.matches(it.width, it.height) }
                .distinctBy { it.id }
                .toList()
        return ImageSearchResult.Success(
            page =
                ImageSearchPage(
                    results = results,
                    // RAW-batch semantics: a fully-filtered page must not
                    // end the scroll while the live lanes still answer.
                    hasMore = rows.size >= RESULTS_PER_PAGE,
                    page = page,
                ),
        )
    }

    /**
     * A corpus row becomes a wallpaper when it carries a usable image
     * URL; web-only rows (no image) are metadata for a future text
     * result list, not a grid tile. `source_host` is the title that
     * matters — the same role `source` plays for bridge results — with
     * the stored caption as fallback. Thumbnails prefer the row's own
     * thumb URL and fall back to the image itself (the corpus's OSS
     * originals are already card-friendly; the bridge proxy would add a
     * Render hop without resizing anything).
     */
    private fun CorpusRowDto.toWallpaper(): Wallpaper? {
        val image = imageUrl?.takeIf { it.startsWith("http") } ?: return null
        return Wallpaper(
            id = "${BrowserSearchEngine.ID_PREFIX}$image",
            providerId = GLOBAL_SEARCH_PROVIDER_ID,
            thumbUrl = thumbUrl?.takeIf { it.startsWith("http") } ?: image,
            fullUrl = image,
            title =
                sourceHost?.takeIf { it.isNotBlank() }
                    ?: title?.takeIf { it.isNotBlank() }?.take(TITLE_MAX_CHARS),
            sourceUrl = pageUrl?.takeIf { it.startsWith("http") },
            width = width?.takeIf { it > 0 },
            height = height?.takeIf { it > 0 },
        )
    }

    @Serializable
    private data class CorpusRowDto(
        @SerialName("image_url") val imageUrl: String? = null,
        @SerialName("thumb_url") val thumbUrl: String? = null,
        @SerialName("page_url") val pageUrl: String? = null,
        val title: String? = null,
        @SerialName("source_host") val sourceHost: String? = null,
        val width: Int? = null,
        val height: Int? = null,
    )

    companion object {
        /** PostgREST table route under the project's /rest/v1. */
        private const val CORPUS_PATH = "rest/v1/search_corpus"

        /** Exactly the columns the wallpaper mapping consumes. */
        private const val SELECT_COLUMNS = "image_url,thumb_url,page_url,title,source_host,width,height"

        /**
         * The corpus's honest depth: pages past this answer empty so deep
         * scroll falls to the live lanes instead of paging into a void.
         */
        internal const val MAX_PAGES = 5

        private const val RESULTS_PER_PAGE = 20
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val TITLE_MAX_CHARS = 80

        private const val CONNECT_TIMEOUT_S = 5L
        private const val READ_TIMEOUT_S = 8L
        private const val CALL_TIMEOUT_S = 10L

        /**
         * The query normalization EVERY corpus writer uses — the harvest
         * seeder and the bridge mirror byte-match this, and so must any
         * future writer, or rows become unreachable from the app.
         */
        internal val WHITESPACE = Regex("\\s+")

        internal fun normalizeQuery(query: String): String = query.trim().lowercase().replace(WHITESPACE, " ")
    }
}
