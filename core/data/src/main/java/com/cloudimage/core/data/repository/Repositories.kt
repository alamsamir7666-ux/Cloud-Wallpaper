package com.cloudimage.core.data.repository

import com.cloudimage.core.model.Downloaded
import com.cloudimage.core.model.Favorite
import com.cloudimage.core.model.HistoryAction
import com.cloudimage.core.model.HistoryEntry
import com.cloudimage.core.model.Wallpaper
import kotlinx.coroutines.flow.Flow

/**
 * Saved wallpapers. Features depend on this interface, never on the Room
 * implementation — tests swap in a fake (see :core:testing).
 */
interface FavoritesRepository {
    fun observeFavorites(): Flow<List<Favorite>>

    fun observeIsFavorite(
        providerId: String,
        wallpaperId: String,
    ): Flow<Boolean>

    /** Adds the wallpaper if absent, removes it if already saved. */
    suspend fun toggleFavorite(wallpaper: Wallpaper)
}

/** Browsing history of viewed / applied / downloaded wallpapers. */
interface HistoryRepository {
    fun observeRecent(limit: Int = DEFAULT_LIMIT): Flow<List<HistoryEntry>>

    suspend fun record(
        wallpaper: Wallpaper,
        action: HistoryAction,
    )

    suspend fun clear()

    companion object {
        const val DEFAULT_LIMIT = 50
    }
}

/**
 * Wallpapers the user downloaded from the app (v1.0.22). Tracked
 * independently of favorites — downloading never favorites, and favoriting
 * never marks downloaded.
 */
interface DownloadsRepository {
    /** Every downloaded wallpaper, newest download first. */
    fun observeDownloads(): Flow<List<Downloaded>>

    fun observeIsDownloaded(
        providerId: String,
        wallpaperId: String,
    ): Flow<Boolean>

    /** Records (or refreshes) the download's snapshot — a plain upsert. */
    suspend fun recordDownload(wallpaper: Wallpaper)
}
