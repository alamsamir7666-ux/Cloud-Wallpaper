package com.cloudimage.unsplash

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
 * Unsplash (https://unsplash.com) as a Cloudimage provider package.
 *
 * Unsplash requires an API key for every endpoint — the provider is
 * keyless-dead, not keyless-degraded: without a stored key every call
 * fails fast with a readable error the host surfaces to the user.
 *
 * Understands the host's `sorting` vocabulary: `date`/`random` map to
 * Unsplash's `order_by=latest`/`relevance` orderings on the search
 * endpoint.
 */
class UnsplashWallpaperProvider : WallpaperProvider {
    private var httpClient: ProviderHttpClient? = null
    private var settings: ProviderSettings? = null

    override val meta =
        ProviderMeta(
            id = ID,
            name = "Unsplash",
            versionName = "1.0.0",
            author = "Cloudimage",
            description = "Free high-resolution photography from unsplash.com.",
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
    ): Result<Page> = photos(page, orderBy = "latest")

    override suspend fun search(
        query: String,
        page: Int,
        filters: Filters,
    ): Result<Page> {
        if (query.isBlank()) {
            return Result.failure(IllegalArgumentException("Unsplash search requires a query"))
        }
        val orderBy =
            when (filters.valuesFor("sorting").firstOrNull()) {
                "date", "toplist" -> "latest"
                else -> "relevant"
            }
        return runCatching {
            val response =
                get(
                    "$BASE_URL/search/photos?query=${encode(query)}&page=$page&per_page=$PER_PAGE" +
                        "&order_by=$orderBy",
                )
            if (!response.isSuccessful) {
                throw httpError(response.statusCode)
            }
            val payload = json.decodeFromString(SearchResponseDto.serializer(), response.bodyText)
            payload.results.toPage(page)
        }
    }

    override suspend fun details(id: String): Result<WallpaperDetails> =
        runCatching {
            val response = get("$BASE_URL/photos/${encode(id)}")
            if (!response.isSuccessful) {
                throw httpError(response.statusCode)
            }
            json.decodeFromString(PhotoDto.serializer(), response.bodyText).toDetails()
        }

    override suspend fun random(): Result<List<Wallpaper>> =
        runCatching {
            val response = get("$BASE_URL/photos/random?count=$PER_PAGE")
            if (!response.isSuccessful) {
                throw httpError(response.statusCode)
            }
            json
                .decodeFromString(ListSerializer, response.bodyText)
                .map { it.toWallpaper() }
        }

    private suspend fun photos(
        page: Int,
        orderBy: String,
    ): Result<Page> =
        runCatching {
            val response = get("$BASE_URL/photos?page=$page&per_page=$PER_PAGE&order_by=$orderBy")
            if (!response.isSuccessful) {
                throw httpError(response.statusCode)
            }
            json.decodeFromString(ListSerializer, response.bodyText).toPage(page)
        }

    private suspend fun get(url: String) =
        httpClient?.get(url, headers = mapOf(AUTH_HEADER to "Client-ID ${apiKey()}"))
            ?: error("configure() was not called")

    private fun apiKey(): String =
        settings?.apiKey(ID)
            ?: throw IllegalStateException("Unsplash requires an API key — add one in the app's extensions screen")

    private fun httpError(statusCode: Int): IllegalStateException =
        IllegalStateException("Unsplash answered HTTP $statusCode (check your API key)")

    private fun List<PhotoDto>.toPage(page: Int): Page =
        Page(
            wallpapers = map { it.toWallpaper() },
            nextPage = if (size >= PER_PAGE) page + 1 else null,
        )

    internal fun PhotoDto.toWallpaper(): Wallpaper =
        Wallpaper(
            id = id,
            providerId = ID,
            thumbUrl = urls.small ?: urls.regular.orEmpty(),
            fullUrl = urls.full ?: urls.regular.orEmpty(),
            title = altDescription ?: description,
            width = width,
            height = height,
            tags = tags.orEmpty().mapNotNull { it.title },
            contentRating = ContentRating.SFW,
        )

    internal fun PhotoDto.toDetails(): WallpaperDetails =
        WallpaperDetails(
            wallpaper = toWallpaper(),
            author = user?.name,
            resolution = if (width != null && height != null) "${width}x$height" else null,
            sourceUrl = links?.html,
        )

    @Serializable
    internal data class PhotoDto(
        val id: String,
        val width: Int? = null,
        val height: Int? = null,
        val description: String? = null,
        @SerialName("alt_description") val altDescription: String? = null,
        val urls: UrlsDto = UrlsDto(),
        val user: UserDto? = null,
        val links: LinksDto? = null,
        val tags: List<TagDto>? = null,
    )

    @Serializable
    internal data class UrlsDto(
        val raw: String? = null,
        val full: String? = null,
        val regular: String? = null,
        val small: String? = null,
        val thumb: String? = null,
    )

    @Serializable
    internal data class UserDto(
        val name: String? = null,
    )

    @Serializable
    internal data class LinksDto(
        val html: String? = null,
    )

    @Serializable
    internal data class TagDto(
        val title: String? = null,
    )

    @Serializable
    private data class SearchResponseDto(
        val results: List<PhotoDto> = emptyList(),
    )

    private companion object {
        const val ID = "cloudimage.unsplash"
        const val BASE_URL = "https://api.unsplash.com"
        const val PER_PAGE = 30
        const val AUTH_HEADER = "Authorization"
        private val ListSerializer = kotlinx.serialization.builtins.ListSerializer(PhotoDto.serializer())
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
    }
}
