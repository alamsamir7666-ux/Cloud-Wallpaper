package com.cloudimage.core.network.wallhaven

import com.cloudimage.core.network.CloudimageHttpClient
import com.cloudimage.core.network.NetworkResult
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Wallhaven content buckets; maps to one digit in the `categories` param. */
enum class WallhavenCategory {
    GENERAL,
    ANIME,
    PEOPLE,
}

/** Wallhaven purity levels; maps to one digit in the `purity` param. */
enum class WallhavenPurity {
    SFW,
    SKETCHY,
    NSFW,
}

enum class WallhavenSorting {
    TOPLIST,
    DATE,
    RANDOM,
    RELEVANCE,
}

enum class WallhavenOrder {
    DESC,
    ASC,
}

/**
 * Everything a Wallhaven search call can express. Values mirror the query
 * parameters documented at https://wallhaven.cc/help/api — the API builder
 * encodes them into the request URL.
 */
data class WallhavenSearchRequest(
    val query: String? = null,
    val categories: Set<WallhavenCategory> =
        setOf(WallhavenCategory.GENERAL, WallhavenCategory.ANIME, WallhavenCategory.PEOPLE),
    val purity: Set<WallhavenPurity> = setOf(WallhavenPurity.SFW),
    val sorting: WallhavenSorting = WallhavenSorting.TOPLIST,
    val order: WallhavenOrder = WallhavenOrder.DESC,
    val page: Int = 1,
    val seed: String? = null,
    val apiKey: String? = null,
)

/**
 * Thin Wallhaven client on the shared [CloudimageHttpClient] — all requests
 * go through the same User-Agent, timeouts and typed-error pipeline as every
 * other provider.
 *
 * [baseUrl] is injectable so unit tests can point at a MockWebServer.
 */
class WallhavenApi(
    private val client: CloudimageHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    /** Runs a search; pagination is expressed by [WallhavenSearchRequest.page]. */
    suspend fun search(request: WallhavenSearchRequest): NetworkResult<WallhavenSearchResponseDto> =
        client.getJson(buildSearchUrl(request), WallhavenSearchResponseDto.serializer())

    /** Fetches a single wallpaper by its Wallhaven id, e.g. "42wq8l". */
    suspend fun wallpaper(id: String): NetworkResult<WallhavenWallpaperDto> {
        val url = baseUrl.toHttpUrl().newBuilder().addPathSegment("w").addPathSegment(id).build()
        return client.getJson(url.toString(), WallhavenWallpaperDto.serializer())
    }

    /** Builds the /search URL — visible for tests so encoding stays covered. */
    internal fun buildSearchUrl(request: WallhavenSearchRequest): String {
        val builder = baseUrl.toHttpUrl().newBuilder().addPathSegment("search")
        request.query?.takeIf { it.isNotBlank() }?.let { builder.addQueryParameter("q", it) }
        builder.addQueryParameter("categories", request.categories.toCategoryParam())
        builder.addQueryParameter("purity", request.purity.toPurityParam())
        builder.addQueryParameter("sorting", request.sorting.toParam())
        builder.addQueryParameter("order", request.order.toParam())
        builder.addQueryParameter("page", request.page.toString())
        request.seed?.takeIf { it.isNotBlank() }?.let { builder.addQueryParameter("seed", it) }
        request.apiKey?.takeIf { it.isNotBlank() }?.let { builder.addQueryParameter("apikey", it) }
        return builder.build().toString()
    }

    private fun Set<WallhavenCategory>.toCategoryParam(): String =
        WallhavenCategory.entries.joinToString(separator = "") { category ->
            if (category in this) "1" else "0"
        }

    private fun Set<WallhavenPurity>.toPurityParam(): String =
        WallhavenPurity.entries.joinToString(separator = "") { purity ->
            if (purity in this) "1" else "0"
        }

    private fun WallhavenSorting.toParam(): String =
        when (this) {
            WallhavenSorting.TOPLIST -> "toplist"
            WallhavenSorting.DATE -> "date"
            WallhavenSorting.RANDOM -> "random"
            WallhavenSorting.RELEVANCE -> "relevance"
        }

    private fun WallhavenOrder.toParam(): String =
        when (this) {
            WallhavenOrder.DESC -> "desc"
            WallhavenOrder.ASC -> "asc"
        }

    companion object {
        const val DEFAULT_BASE_URL = "https://wallhaven.cc/api/v1"

        /** Bound in NetworkModule so features never construct this directly. */
        const val PROVIDER_ID = "wallhaven"
    }
}
