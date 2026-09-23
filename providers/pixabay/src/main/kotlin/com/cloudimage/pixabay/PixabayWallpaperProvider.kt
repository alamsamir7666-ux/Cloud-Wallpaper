package com.cloudimage.pixabay

import com.cloudimage.provider.api.Capability
import com.cloudimage.provider.api.ContentRating
import com.cloudimage.provider.api.Filters
import com.cloudimage.provider.api.Page
import com.cloudimage.provider.api.ProviderHttpClient
import com.cloudimage.provider.api.ProviderMeta
import com.cloudimage.provider.api.ProviderSettings
import com.cloudimage.provider.api.Wallpaper
import com.cloudimage.provider.api.WallpaperDetails
import com.cloudimage.provider.api.WallpaperProvider
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URLEncoder

/**
 * Pixabay (https://pixabay.com) as a Cloudimage provider package.
 *
 * The key travels as a `key` query parameter (Pixabay has no header
 * auth). Safe search stays on unconditionally — Pixabay's whole catalog
 * is family-friendly, which is why the provider never sees purity
 * filters. Understands the host's `sorting` vocabulary: `date` maps to
 * Pixabay's `order=latest`, everything else to `order=popular`.
 */
class PixabayWallpaperProvider : WallpaperProvider {
    private var httpClient: ProviderHttpClient? = null
    private var settings: ProviderSettings? = null

    override val meta =
        ProviderMeta(
            id = ID,
            name = "Pixabay",
            versionName = "1.0.0",
            author = "Cloudimage",
            description = "Free images and vector art from pixabay.com.",
            requiresApiKey = true,
        )

    override val capabilities: Set<Capability> = setOf(Capability.POPULAR, Capability.LATEST, Capability.SEARCH)

    override fun configure(
        client: ProviderHttpClient,
        settings: ProviderSettings,
    ) {
        httpClient = client
        this.settings = settings
    }

    override suspend fun popular(
        page: Int,
        filters: Filters,
    ): Result<Page> = fetch(query = null, filters = filters, page = page)

    override suspend fun search(
        query: String,
        page: Int,
        filters: Filters,
    ): Result<Page> {
        if (query.isBlank()) {
            return Result.failure(IllegalArgumentException("Pixabay search requires a query"))
        }
        return fetch(query, filters, page)
    }

    override suspend fun details(id: String): Result<WallpaperDetails> =
        Result.failure(UnsupportedOperationException("Pixabay has no per-image endpoint"))

    override suspend fun random(): Result<List<Wallpaper>> = Result.failure(UnsupportedOperationException("Pixabay has no random endpoint"))

    private suspend fun fetch(
        query: String?,
        filters: Filters,
        page: Int,
    ): Result<Page> =
        runCatching {
            val order =
                when (filters.valuesFor("sorting").firstOrNull()) {
                    "date" -> "latest"
                    else -> "popular"
                }
            val url =
                buildString {
                    append("$BASE_URL/api/?key=").append(apiKey())
                    append("&page=").append(page).append("&per_page=").append(PER_PAGE)
                    append("&image_type=photo&safesearch=true&order=").append(order)
                    if (query != null) {
                        append("&q=").append(encode(query))
                    }
                }
            val response = get(url)
            if (!response.isSuccessful) {
                throw httpError(response.statusCode)
            }
            val payload = json.decodeFromString(SearchResponseDto.serializer(), response.bodyText)
            Page(
                wallpapers = payload.hits.map { it.toWallpaper() },
                nextPage = if (payload.hits.size >= PER_PAGE) page + 1 else null,
            )
        }

    private suspend fun get(url: String) = httpClient?.get(url) ?: error("configure() was not called")

    private fun apiKey(): String =
        settings?.apiKey(ID)
            ?: throw IllegalStateException("Pixabay requires an API key — add one in the app's extensions screen")

    private fun httpError(statusCode: Int): IllegalStateException =
        IllegalStateException("Pixabay answered HTTP $statusCode (check your API key)")

    internal fun HitDto.toWallpaper(): Wallpaper =
        Wallpaper(
            id = id.toString(),
            providerId = ID,
            thumbUrl = webformatUrl.orEmpty(),
            fullUrl = fullHdUrl ?: largeImageUrl.orEmpty(),
            title = tags.split(",").map(String::trim).filter(String::isNotEmpty).firstOrNull(),
            width = imageWidth,
            height = imageHeight,
            tags = tags.split(",").map(String::trim).filter(String::isNotEmpty),
            contentRating = ContentRating.SFW,
        )

    @Serializable
    internal data class HitDto(
        val id: Long,
        @SerialName("pageURL") val pageUrl: String? = null,
        val tags: String = "",
        @SerialName("previewURL") val previewUrl: String? = null,
        @SerialName("webformatURL") val webformatUrl: String? = null,
        @SerialName("largeImageURL") val largeImageUrl: String? = null,
        @SerialName("fullHDURL") val fullHdUrl: String? = null,
        @SerialName("imageWidth") val imageWidth: Int? = null,
        @SerialName("imageHeight") val imageHeight: Int? = null,
        val user: String? = null,
    )

    @Serializable
    private data class SearchResponseDto(
        val hits: List<HitDto> = emptyList(),
        @SerialName("totalHits") val totalHits: Int? = null,
    )

    private companion object {
        const val ID = "cloudimage.pixabay"
        const val BASE_URL = "https://pixabay.com"
        const val PER_PAGE = 30
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
    }
}
