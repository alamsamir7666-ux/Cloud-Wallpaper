package com.cloudimage.core.data.repository

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
