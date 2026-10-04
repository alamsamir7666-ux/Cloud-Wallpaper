package com.cloudimage.core.search

import com.cloudimage.core.model.Wallpaper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * The global image search engine backed by the Browser repo system.
 *
 * The system's own engine (the z-ai image-search service) answers each
 * query with one capped batch — no offset, no cursor — so its pagination
 * strategy is query-modifier based: every page re-searches the query with
 * a different modifier appended ("hd", "high resolution", "wallpaper",
 * "4k"…) to surface a fresh batch, and the caller deduplicates by URL.
 * That is why this engine's contract deliberately mirrors it: [page] is a
 * plain 1-based counter, [ImageSearchPage.hasMore] is the backend's own
 * "there are more modifiers to try", and result ids are derived from the
 * image URL so the same image found under two modifiers collapses into
 * one card.
 *
 * Everything the app needs to know about a result is already in the
 * backend's answer — including exact pixel dimensions — which keeps the
 * [SearchSizeTier] an exact client-side verification rather than a hope.
 * A chosen tier also biases the query itself (see [SearchSizeTier.queryToken]):
 * the backend's own pagination already rides query modifiers, so an
 * extra tier token stacks with them — every page then leans toward the
 * size the user asked for, while the exact verification still refuses
 * anything the dims cannot prove.
 *
 * v1.2.1: the bridge's address is mortal, so failures that a live bridge
 * would not produce (transport errors, timeouts, HTTP error statuses)
 * now tell the [SearchBackendConfig] it is stale — the next search
 * re-fetches the remote override and follows a republished address
 * without waiting for a process restart. The grid's thumbnails also ride
 * the backend's image proxy at card width, so the phone stops paying
 * full-resolution bytes for images it shows at a few hundred pixels.
 */
class BrowserSearchEngine(
    private val config: SearchBackendConfig,
    baseClient: OkHttpClient,
    private val json: Json,
    /** Tests point this at MockWebServer; production leaves it null and [config] decides. */
    private val overrideBaseUrl: String? = null,
) : ImageSearchEngine {
    override val name: String = "Browser"

    /**
     * The backend's upstream search can legitimately take tens of seconds
     * (it probes reachability and mirrors images before answering), so the
     * app-wide client's defaults are far too eager here. Derived clients
     * share the app client's pools and interceptors.
     */
    private val slowClient =
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
            val base = (overrideBaseUrl ?: config.baseUrl()).trimEnd('/')
            // base is captured here so the parser can build proxy thumb
            // URLs that share the bridge that served the search results.
            val payload =
                json.encodeToString(
                    SearchRequestDto.serializer(),
                    SearchRequestDto(
                        query = filters.sizeTier.queryToken()?.let { "$query $it" } ?: query,
                        count = RESULTS_PER_PAGE,
                        page = page,
                    ),
                )
            val request =
                Request
                    .Builder()
                    .url("$base/$SEARCH_PATH")
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()

            val response =
                try {
                    slowClient.newCall(request).execute()
                } catch (e: SocketTimeoutException) {
                    // A bridge that cannot answer inside 160 s is not slow —
                    // it is gone. Mark the config stale so the next search
                    // re-fetches the remote override before trusting the
                    // address again.
                    if (overrideBaseUrl == null) config.invalidate()
                    return@withContext ImageSearchResult.Failure(
                        error = ImageSearchError.TIMEOUT,
                        detail = e.message,
                    )
                } catch (e: IOException) {
                    // UnknownHostException and friends are the exact
                    // signature of a re-published tunnel: the old address
                    // simply stops resolving. Same cure as a timeout.
                    if (overrideBaseUrl == null) config.invalidate()
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

                    !answered.isSuccessful -> {
                        // The recycled workspace answered 410 Gone; a dead
                        // tunnel answers 5xx. A live bridge answers 2xx (or
                        // 429 above) — so anything else means the address
                        // is suspect and the config is stale until the
                        // next fetch proves otherwise.
                        if (overrideBaseUrl == null) config.invalidate()
                        ImageSearchResult.Failure(
                            error = ImageSearchError.SERVER,
                            detail = "HTTP ${answered.code}",
                        )
                    }

                    else -> parse(body, filters, base)
                }
            }
        }

    private fun parse(
        body: String,
        filters: GlobalSearchFilters,
        base: String,
    ): ImageSearchResult {
        val dto =
            try {
                json.decodeFromString(SearchResponseDto.serializer(), body)
            } catch (e: SerializationException) {
                return ImageSearchResult.Failure(
                    error = ImageSearchError.BAD_RESPONSE,
                    detail = e.message,
                )
            }
        if (!dto.success) {
            return ImageSearchResult.Failure(
                error = ImageSearchError.SERVER,
                detail = dto.error,
            )
        }
        val results =
            dto.results
                .asSequence()
                .filter { it.originalUrl.isNotBlank() }
                .distinctBy { it.originalUrl }
                .map { it.toWallpaper(base) }
                .filter { filters.sizeTier.matches(it.width, it.height) }
                .toList()
        return ImageSearchResult.Success(
            page =
                ImageSearchPage(
                    results = results,
                    hasMore = dto.hasMore,
                    page = dto.page,
                ),
        )
    }

    private fun ImageResultDto.toWallpaper(base: String): Wallpaper =
        Wallpaper(
            id = "$ID_PREFIX$originalUrl",
            providerId = GLOBAL_SEARCH_PROVIDER_ID,
            // v1.2.1: thumbnails ride the backend's image proxy at card
            // width — the phone stops paying for full-resolution originals
            // it will only ever show a few hundred pixels wide. The detail
            // screen, downloads and share keep the untouched original,
            // where quality is the whole point.
            thumbUrl = buildProxyUrl(base, originalUrl, THUMB_WIDTH),
            fullUrl = originalUrl,
            // The backend searches with ranking off for speed, so captions
            // arrive empty and `source` — the site's name — is the title
            // that matters: it is what the grid's provenance overlay and
            // the share sheet both show.
            title = source?.takeIf { it.isNotBlank() },
            width = originalWidth.asDimension(),
            height = originalHeight.asDimension(),
        )

    /**
     * The backend normalizes dimensions to plain numbers, but the upstream
     * reports them as strings like `"1400px"` — tolerate both so a route
     * tweak upstream of us never breaks parsing. Zero and garbage mean
     * "unknown" and stay null: the size tiers verify exactly or not at all.
     */
    private fun JsonElement?.asDimension(): Int? {
        val raw = (this as? JsonPrimitive)?.contentOrNull ?: return null
        return DIMENSION_REGEX
            .find(raw)
            ?.value
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
    }

    @Serializable
    private data class SearchRequestDto(
        val query: String,
        val count: Int,
        val page: Int,
        val gl: String = GL,
    )

    @Serializable
    private data class SearchResponseDto(
        val success: Boolean = false,
        val query: String? = null,
        val count: Int = 0,
        val page: Int = 1,
        val hasMore: Boolean = false,
        val results: List<ImageResultDto> = emptyList(),
        val error: String? = null,
    )

    @Serializable
    private data class ImageResultDto(
        val id: String? = null,
        @SerialName("original_url") val originalUrl: String = "",
        val caption: String? = null,
        val source: String? = null,
        @SerialName("original_width") val originalWidth: JsonElement? = null,
        @SerialName("original_height") val originalHeight: JsonElement? = null,
    )

    companion object {
        /** Result ids are the image URL under this prefix — stable across pages, as the contract demands. */
        const val ID_PREFIX = "gs:"

        private const val SEARCH_PATH = "api/search"
        private const val GL = "us"
        private const val RESULTS_PER_PAGE = 20
        private const val HTTP_TOO_MANY_REQUESTS = 429

        /**
         * The width the grid's thumbnails ask the backend proxy for — the
         * Medium variant's own 640, comfortably above a 2-column card on
         * a dense phone while a fraction of the original's bytes.
         */
        private const val THUMB_WIDTH = 640

        /** The backend's own CLI budget is 120s; read timeout sits above it. */
        private const val CONNECT_TIMEOUT_S = 15L
        private const val READ_TIMEOUT_S = 150L
        private const val CALL_TIMEOUT_S = 160L

        private val DIMENSION_REGEX = Regex("""\d+""")
    }
}
