package com.cloudimage.wallhaven

import com.cloudimage.provider.api.Capability
import com.cloudimage.provider.api.ContentRating
import com.cloudimage.provider.api.Filters
import com.cloudimage.provider.api.Page
import com.cloudimage.provider.api.ProviderHttpClient
import com.cloudimage.provider.api.ProviderHttpResponse
import com.cloudimage.provider.api.ProviderMeta
import com.cloudimage.provider.api.ProviderSettings
import com.cloudimage.provider.api.Wallpaper
import com.cloudimage.provider.api.WallpaperDetails
import com.cloudimage.provider.api.WallpaperProvider
import java.net.URLEncoder

/**
 * Wallhaven (https://wallhaven.cc) as a Cloudimage provider package.
 *
 * Keyless by design in V1: NSFW is never requested (the API requires a key
 * for it), so the app's SFW switch is honored through the host-side purity
 * filters alone.
 *
 * The provider speaks the host's filter vocabulary (see the Filters KDoc):
 * `category`, `purity`, `sorting`, `order`, `seed`.
 */
class WallhavenWallpaperProvider : WallpaperProvider {
    private var httpClient: ProviderHttpClient? = null

    override val meta =
        ProviderMeta(
            id = ID,
            name = "Wallhaven",
            versionName = "1.0.0",
            author = "Cloudimage",
            description = "Wallpapers from wallhaven.cc.",
            contentRating = ContentRating.SKETCHY,
        )

    override val capabilities: Set<Capability> =
        setOf(Capability.POPULAR, Capability.LATEST, Capability.SEARCH, Capability.RANDOM, Capability.FILTERS)

    override fun configure(
        client: ProviderHttpClient,
        settings: ProviderSettings,
    ) {
        httpClient = client
    }

    override suspend fun popular(
        page: Int,
        filters: Filters,
    ): Result<Page> = runCatching { fetchPage(null, filters, page) }

    override suspend fun search(
        query: String,
        page: Int,
        filters: Filters,
    ): Result<Page> = runCatching { fetchPage(query, filters, page) }

    override suspend fun details(id: String): Result<WallpaperDetails> =
        runCatching {
            val response = get("$BASE_URL/w/${encode(id)}")
            if (!response.isSuccessful) {
                throw httpError(response.statusCode)
            }
            json.decodeFromString(WallhavenWallpaperDto.serializer(), response.bodyText).toDetails()
        }

    override suspend fun random(): Result<List<Wallpaper>> =
        runCatching {
            fetchPage(query = null, filters = Filters.of("sorting" to "random"), page = 1).wallpapers
        }

    private suspend fun fetchPage(
        query: String?,
        filters: Filters,
        page: Int,
    ): Page {
        val response = get(buildSearchUrl(query, filters, page))
        if (!response.isSuccessful) {
            throw httpError(response.statusCode)
        }
        val payload = json.decodeFromString(WallhavenSearchResponseDto.serializer(), response.bodyText)
        return Page(
            wallpapers = payload.data.map { it.toWallpaper() },
            nextPage = if (payload.meta.hasNextPage) payload.meta.currentPage!! + 1 else null,
        )
    }

    private suspend fun get(url: String): ProviderHttpResponse =
        httpClient?.get(url)
            ?: error("configure() was not called")

    /** Builds the /search URL — visible for tests so encoding stays covered. */
    internal fun buildSearchUrl(
        query: String?,
        filters: Filters,
        page: Int,
    ): String {
        val builder = UrlBuilder("$BASE_URL/search")
        query?.takeIf { it.isNotBlank() }?.let { builder.param("q", it) }
        builder.param("categories", categoriesParam(filters))
        builder.param("purity", purityParam(filters))
        builder.param("sorting", sortingParam(filters))
        builder.param("order", if (filters.isSelected("order", "asc")) "asc" else "desc")
        builder.param("page", page.toString())
        filters.valuesFor("seed").firstOrNull()?.takeIf { it.isNotBlank() }?.let { builder.param("seed", it) }
        return builder.build()
    }

    /** "110"-style category flags in Wallhaven's fixed order; no selection means all. */
    private fun categoriesParam(filters: Filters): String {
        val selected = listOf("general", "anime", "people").filter { filters.isSelected("category", it) }
        return if (selected.isEmpty()) {
            "111"
        } else {
            listOf("general", "anime", "people").joinToString(separator = "") { category ->
                if (category in selected) "1" else "0"
            }
        }
    }

    /**
     * The clamp: purity defaults to SFW, NSFW is never requestable (it
     * needs an API key), and an empty selection degrades to SFW so
     * Wallhaven always receives a valid flag string like "100".
     */
    internal fun purityParam(filters: Filters): String {
        val requested =
            buildSet {
                if (filters.isSelected("purity", "sfw")) add("sfw")
                if (filters.isSelected("purity", "sketchy")) add("sketchy")
            }
        val effective = requested.ifEmpty { setOf("sfw") }
        return listOf("sfw", "sketchy", "nsfw").joinToString(separator = "") { purity ->
            if (purity in effective) "1" else "0"
        }
    }

    private fun sortingParam(filters: Filters): String =
        when (filters.valuesFor("sorting").firstOrNull()) {
            "date" -> "date"
            "random" -> "random"
            "relevance" -> "relevance"
            else -> "toplist"
        }

    private fun httpError(statusCode: Int): IllegalStateException = IllegalStateException("wallhaven answered HTTP $statusCode")

    internal fun WallhavenWallpaperDto.toWallpaper(): Wallpaper =
        Wallpaper(
            id = id,
            providerId = ID,
            thumbUrl = thumbs?.large ?: thumbs?.original ?: path.orEmpty(),
            fullUrl = path.orEmpty(),
            title = null,
            width = dimensionX,
            height = dimensionY,
            tags = colors,
            contentRating =
                when (purity) {
                    "sketchy" -> ContentRating.SKETCHY
                    "nsfw" -> ContentRating.NSFW
                    else -> ContentRating.SFW
                },
        )

    internal fun WallhavenWallpaperDto.toDetails(): WallpaperDetails =
        WallpaperDetails(
            wallpaper = toWallpaper(),
            author = null,
            resolution = resolution,
            fileSizeBytes = fileSize,
            sourceUrl = url,
        )

    private companion object {
        const val ID = "cloudimage.wallhaven"
        const val BASE_URL = "https://wallhaven.cc/api/v1"
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
    }
}

/** Minimal query-string builder — plugins have no HTTP library of their own. */
internal class UrlBuilder(
    private val base: String,
) {
    private val params = mutableListOf<Pair<String, String>>()

    fun param(
        name: String,
        value: String,
    ): UrlBuilder {
        params += name to value
        return this
    }

    fun build(): String =
        base +
            params.joinToString(prefix = "?", separator = "&") { (name, value) ->
                val encoded =
                    URLEncoder.encode(value, Charsets.UTF_8.name())
                        .replace("+", "%20")
                        .replace("%2F", "/")
                        .replace("%3A", ":")
                "$name=$encoded"
            }
}
