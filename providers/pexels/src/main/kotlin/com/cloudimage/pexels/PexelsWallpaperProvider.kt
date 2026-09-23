package com.cloudimage.pexels

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
 * Pexels (https://pexels.com) as a Cloudimage provider package.
 *
 * Pexels requires an API key (free tier: 200 requests/hour) sent as a
 * plain Authorization header. The curated endpoint serves as the popular
 * feed; search supports free text. Pexels has no category or purity
 * buckets, so other host filters are ignored.
 */
class PexelsWallpaperProvider : WallpaperProvider {
    private var httpClient: ProviderHttpClient? = null
    private var settings: ProviderSettings? = null

    override val meta =
        ProviderMeta(
            id = ID,
            name = "Pexels",
            versionName = "1.0.0",
            author = "Cloudimage",
            description = "Free stock photos and wallpapers from pexels.com.",
            requiresApiKey = true,
        )

    override val capabilities: Set<Capability> = setOf(Capability.POPULAR, Capability.SEARCH)

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
    ): Result<Page> = feed("/v1/curated", query = null, page = page)

    override suspend fun search(
        query: String,
        page: Int,
        filters: Filters,
    ): Result<Page> {
        if (query.isBlank()) {
            return Result.failure(IllegalArgumentException("Pexels search requires a query"))
        }
        return feed("/v1/search", query = query, page = page)
    }

    override suspend fun details(id: String): Result<WallpaperDetails> =
        runCatching {
            val response = get("$BASE_URL/v1/photos/${encode(id)}")
            if (!response.isSuccessful) {
                throw httpError(response.statusCode)
            }
            json.decodeFromString(PhotoDto.serializer(), response.bodyText).toDetails()
        }

    override suspend fun random(): Result<List<Wallpaper>> =
        // Pexels has no random endpoint; serve the first curated page.
        popular(page = 1).map { it.wallpapers }

    private suspend fun feed(
        path: String,
        query: String?,
        page: Int,
    ): Result<Page> =
        runCatching {
            val url =
                buildString {
                    append(BASE_URL).append(path).append("?per_page=").append(PER_PAGE).append("&page=").append(page)
                    if (query != null) {
                        append("&query=").append(encode(query))
                    }
                }
            val response = get(url)
            if (!response.isSuccessful) {
                throw httpError(response.statusCode)
            }
            val payload = json.decodeFromString(FeedResponseDto.serializer(), response.bodyText)
            Page(
                wallpapers = payload.photos.map { it.toWallpaper() },
                nextPage = payload.nextPage?.let { page + 1 },
            )
        }

    private suspend fun get(url: String) =
        httpClient?.get(url, headers = mapOf(AUTH_HEADER to apiKey()))
            ?: error("configure() was not called")

    private fun apiKey(): String =
        settings?.apiKey(ID)
            ?: throw IllegalStateException("Pexels requires an API key — add one in the app's extensions screen")

    private fun httpError(statusCode: Int): IllegalStateException =
        IllegalStateException("Pexels answered HTTP $statusCode (check your API key)")

    internal fun PhotoDto.toWallpaper(): Wallpaper =
        Wallpaper(
            id = id.toString(),
            providerId = ID,
            thumbUrl = src.medium ?: src.large.orEmpty(),
            fullUrl = src.original ?: src.large.orEmpty(),
            title = alt,
            width = width,
            height = height,
            contentRating = ContentRating.SFW,
        )

    internal fun PhotoDto.toDetails(): WallpaperDetails =
        WallpaperDetails(
            wallpaper = toWallpaper(),
            author = photographer,
            resolution = if (width != null && height != null) "${width}x$height" else null,
            sourceUrl = url,
        )

    @Serializable
    internal data class PhotoDto(
        val id: Long,
        val width: Int? = null,
        val height: Int? = null,
        val url: String? = null,
        val alt: String? = null,
        val photographer: String? = null,
        val src: SrcDto = SrcDto(),
    )

    @Serializable
    internal data class SrcDto(
        val original: String? = null,
        val large2x: String? = null,
        val large: String? = null,
        val medium: String? = null,
        val small: String? = null,
        val portrait: String? = null,
        val landscape: String? = null,
        val tiny: String? = null,
    )

    @Serializable
    private data class FeedResponseDto(
        val photos: List<PhotoDto> = emptyList(),
        @SerialName("next_page") val nextPage: String? = null,
    )

    private companion object {
        const val ID = "cloudimage.pexels"
        const val BASE_URL = "https://api.pexels.com"
        const val PER_PAGE = 30
        const val AUTH_HEADER = "Authorization"
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
    }
}
