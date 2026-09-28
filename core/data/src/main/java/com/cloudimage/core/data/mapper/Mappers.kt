package com.cloudimage.core.data.mapper

import com.cloudimage.core.database.DownloadedEntity
import com.cloudimage.core.database.FavoriteEntity
import com.cloudimage.core.database.HistoryEntity
import com.cloudimage.core.model.ContentRating
import com.cloudimage.core.model.Downloaded
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

internal fun Wallpaper.toDownloadedEntity(): DownloadedEntity =
    DownloadedEntity(
        providerId = providerId,
        wallpaperId = id,
        thumbUrl = thumbUrl,
        fullUrl = fullUrl,
        title = title,
        width = width,
        height = height,
        sourceUrl = sourceUrl,
        contentRating = contentRating.name,
        downloadedAtMillis = System.currentTimeMillis(),
    )

internal fun DownloadedEntity.toDownloaded(): Downloaded =
    Downloaded(
        wallpaper = toWallpaper(),
        downloadedAtMillis = downloadedAtMillis,
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

private fun HistoryEntity.toWallpaper(): Wallpaper =
    snapshotToWallpaper(
        providerId = providerId,
        wallpaperId = wallpaperId,
        thumbUrl = thumbUrl,
        fullUrl = fullUrl,
        title = title,
        width = width,
        height = height,
        sourceUrl = sourceUrl,
        contentRating = contentRating,
    )

private fun FavoriteEntity.toWallpaper(): Wallpaper =
    snapshotToWallpaper(
        providerId = providerId,
        wallpaperId = wallpaperId,
        thumbUrl = thumbUrl,
        fullUrl = fullUrl,
        title = title,
        width = width,
        height = height,
        sourceUrl = sourceUrl,
        contentRating = contentRating,
    )

private fun DownloadedEntity.toWallpaper(): Wallpaper =
    snapshotToWallpaper(
        providerId = providerId,
        wallpaperId = wallpaperId,
        thumbUrl = thumbUrl,
        fullUrl = fullUrl,
        title = title,
        width = width,
        height = height,
        sourceUrl = sourceUrl,
        contentRating = contentRating,
    )

/** All three snapshot entities carry the same fields; this is their one mapper. */
private fun snapshotToWallpaper(
    providerId: String,
    wallpaperId: String,
    thumbUrl: String,
    fullUrl: String,
    title: String?,
    width: Int?,
    height: Int?,
    sourceUrl: String?,
    contentRating: String,
): Wallpaper =
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
