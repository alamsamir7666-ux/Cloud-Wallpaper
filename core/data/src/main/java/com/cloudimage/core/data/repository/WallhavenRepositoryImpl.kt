package com.cloudimage.core.data.repository

import com.cloudimage.core.model.ContentRating
import com.cloudimage.core.model.Page
import com.cloudimage.core.model.Wallpaper
import com.cloudimage.core.model.WallpaperCategory
import com.cloudimage.core.model.WallpaperDetails
import com.cloudimage.core.model.WallpaperQuery
import com.cloudimage.core.model.WallpaperSorting
import com.cloudimage.core.network.NetworkResult
import com.cloudimage.core.network.map
import com.cloudimage.core.network.wallhaven.WallhavenApi
import com.cloudimage.core.network.wallhaven.WallhavenCategory
import com.cloudimage.core.network.wallhaven.WallhavenOrder
import com.cloudimage.core.network.wallhaven.WallhavenPurity
import com.cloudimage.core.network.wallhaven.WallhavenSearchRequest
import com.cloudimage.core.network.wallhaven.WallhavenSorting
import com.cloudimage.core.network.wallhaven.WallhavenWallpaperDto
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-free counterpart of the Room repositories: translates the
 * provider-agnostic [WallpaperQuery] into Wallhaven API calls and maps wire
 * types into domain models.
 *
 * Content safety is enforced here, not in the UI: whatever the user selects,
 * a request without an API key can never ask Wallhaven for NSFW content, and
 * an empty selection degrades to SFW rather than an invalid request.
 */
@Singleton
class WallhavenRepositoryImpl
    @Inject
    constructor(
        private val api: WallhavenApi,
    ) : WallhavenRepository {
        override suspend fun search(
            query: WallpaperQuery,
            page: Int,
        ): NetworkResult<Page> =
            api.search(query.toRequest(page)).map { response ->
                Page(
                    wallpapers = response.data.map { it.toWallpaper() },
                    nextPage =
                        with(response.meta) {
                            if (hasNextPage) currentPage!! + 1 else null
                        },
                )
            }

        override suspend fun getWallpaper(wallpaperId: String): NetworkResult<WallpaperDetails> =
            api.wallpaper(wallpaperId).map { dto ->
                WallpaperDetails(
                    wallpaper = dto.toWallpaper(),
                    resolution = dto.resolution,
                    fileSizeBytes = dto.fileSize,
                )
            }

        companion object {
            /** Identity half of every [Wallpaper] this repository produces. */
            const val PROVIDER_ID = "wallhaven"
        }
    }

/** Domain query -> Wallhaven request, including the content-safety clamp. */
private fun WallpaperQuery.toRequest(page: Int): WallhavenSearchRequest =
    WallhavenSearchRequest(
        query = text.takeIf { it.isNotBlank() },
        categories =
            categories
                .mapTo(LinkedHashSet()) { it.toWallhavenCategory() }
                .ifEmpty { WallhavenCategory.entries.toSet() },
        purity = sanitizedPurity(),
        sorting =
            when (sorting) {
                WallpaperSorting.TOPLIST -> WallhavenSorting.TOPLIST
                WallpaperSorting.DATE -> WallhavenSorting.DATE
                WallpaperSorting.RANDOM -> WallhavenSorting.RANDOM
                WallpaperSorting.RELEVANCE -> WallhavenSorting.RELEVANCE
            },
        order = if (descending) WallhavenOrder.DESC else WallhavenOrder.ASC,
        page = page,
        seed = seed,
    )

/**
 * The clamp: drop NSFW (never requestable in V1 — no API key), then fall back
 * to SFW when nothing survives, so Wallhaven always receives a valid flag
 * string like "100" or "110" — never "000".
 */
private fun WallpaperQuery.sanitizedPurity(): Set<WallhavenPurity> {
    val requested =
        contentRatings.mapNotNull { rating ->
            when (rating) {
                ContentRating.SFW -> WallhavenPurity.SFW
                ContentRating.SKETCHY -> WallhavenPurity.SKETCHY
                // Not requestable without an API key; silently dropped.
                ContentRating.NSFW -> null
            }
        }.toSet()
    return requested.ifEmpty { setOf(WallhavenPurity.SFW) }
}

private fun WallpaperCategory.toWallhavenCategory(): WallhavenCategory =
    when (this) {
        WallpaperCategory.GENERAL -> WallhavenCategory.GENERAL
        WallpaperCategory.ANIME -> WallhavenCategory.ANIME
        WallpaperCategory.PEOPLE -> WallhavenCategory.PEOPLE
    }

/** Wire type -> domain model; picks the best thumbnail Wallhaven offers. */
internal fun WallhavenWallpaperDto.toWallpaper(): Wallpaper =
    Wallpaper(
        id = id,
        providerId = WallhavenRepositoryImpl.PROVIDER_ID,
        thumbUrl = thumbs?.large ?: thumbs?.original ?: path.orEmpty(),
        fullUrl = path.orEmpty(),
        title = null,
        width = dimensionX,
        height = dimensionY,
        sourceUrl = url,
        contentRating = purity.toContentRating(),
    )

internal fun String?.toContentRating(): ContentRating =
    when (this) {
        "sketchy" -> ContentRating.SKETCHY
        "nsfw" -> ContentRating.NSFW
        else -> ContentRating.SFW
    }
