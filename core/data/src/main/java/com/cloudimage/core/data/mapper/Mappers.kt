package com.cloudimage.core.data.mapper

import com.cloudimage.core.database.FavoriteEntity
import com.cloudimage.core.database.HistoryEntity
import com.cloudimage.core.model.ContentRating
import com.cloudimage.core.model.Favorite
import com.cloudimage.core.model.HistoryAction
import com.cloudimage.core.model.HistoryEntry
import com.cloudimage.core.model.Wallpaper

/** Converts domain objects to Room entities — storage-side mapping. */

internal fun Wallpaper.toFavoriteEntity(): FavoriteEntity =
    FavoriteEntity(
        providerId = providerId,
        wallpaperId = id,
        thumbUrl = thumbUrl,
        fullUrl = fullUrl,
        title = title,
        width = width,
        height = height,
        sourceUrl = sourceUrl,
        contentRating = contentRating.name,
        addedAtMillis = System.currentTimeMillis(),
    )

internal fun FavoriteEntity.toFavorite(): Favorite =
    Favorite(
        wallpaper = toWallpaper(),
        addedAtMillis = addedAtMillis,
    )

internal fun Wallpaper.toHistoryEntity(
    action: HistoryAction,
    atMillis: Long,
): HistoryEntity =
    HistoryEntity(
        providerId = providerId,
        wallpaperId = id,
        thumbUrl = thumbUrl,
        fullUrl = fullUrl,
        title = title,
        width = width,
        height = height,
        sourceUrl = sourceUrl,
        contentRating = contentRating.name,
        action = action.name,
        atMillis = atMillis,
    )

internal fun HistoryEntity.toHistoryEntry(): HistoryEntry =
    HistoryEntry(
        wallpaper = toWallpaper(),
        action = runCatching { HistoryAction.valueOf(action) }.getOrDefault(HistoryAction.VIEWED),
        atMillis = atMillis,
    )

private fun FavoriteEntity.toWallpaper(): Wallpaper =
    Wallpaper(
        id = wallpaperId,
        providerId = providerId,
        thumbUrl = thumbUrl,
        fullUrl = fullUrl,
        title = title,
        width = width,
        height = height,
        sourceUrl = sourceUrl,
        contentRating =
            runCatching { ContentRating.valueOf(contentRating) }
                .getOrDefault(ContentRating.SFW),
    )

private fun HistoryEntity.toWallpaper(): Wallpaper =
    Wallpaper(
        id = wallpaperId,
        providerId = providerId,
        thumbUrl = thumbUrl,
        fullUrl = fullUrl,
        title = title,
        width = width,
        height = height,
        sourceUrl = sourceUrl,
        contentRating =
            runCatching { ContentRating.valueOf(contentRating) }
                .getOrDefault(ContentRating.SFW),
    )
