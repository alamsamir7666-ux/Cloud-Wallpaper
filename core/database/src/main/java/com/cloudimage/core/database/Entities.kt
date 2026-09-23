package com.cloudimage.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A wallpaper the user saved, with a snapshot of the fields the UI needs to
 * render it even if the source later changes or disappears.
 *
 * Identity is the (providerId, wallpaperId) pair — the same wallpaper can come
 * from different providers.
 */
@Entity(tableName = "favorites", primaryKeys = ["providerId", "wallpaperId"])
data class FavoriteEntity(
    val providerId: String,
    val wallpaperId: String,
    val thumbUrl: String,
    val fullUrl: String,
    val title: String?,
    val width: Int?,
    val height: Int?,
    val sourceUrl: String?,
    val contentRating: String,
    val addedAtMillis: Long,
)

/**
 * One user action on a wallpaper (viewed / applied / downloaded) with a
 * snapshot for rendering. Rows are append-only and pruned by age in later parts.
 */
@Entity(
    tableName = "history",
    indices = [
        Index(value = ["atMillis"]),
        Index(value = ["providerId", "wallpaperId"]),
    ],
)
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: String,
    val wallpaperId: String,
    val thumbUrl: String,
    val fullUrl: String,
    val title: String?,
    val width: Int?,
    val height: Int?,
    val sourceUrl: String?,
    val contentRating: String,
    val action: String,
    val atMillis: Long,
)
