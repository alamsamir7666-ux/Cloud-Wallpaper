package com.cloudimage.core.testing

import com.cloudimage.core.data.repository.FavoritesRepository
import com.cloudimage.core.data.repository.HistoryRepository
import com.cloudimage.core.model.Favorite
import com.cloudimage.core.model.HistoryAction
import com.cloudimage.core.model.HistoryEntry
import com.cloudimage.core.model.Wallpaper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Test doubles for repositories, shared by every module's unit tests
 * (:core:testing exists so test code never depends on Room or Hilt).
 *
 * Tests can either drive them through the interface, or seed/inspect state
 * directly through the test hooks at the bottom of each class.
 */
class FakeFavoritesRepository : FavoritesRepository {
    private val _favorites = MutableStateFlow<List<Favorite>>(emptyList())
    val favorites: List<Favorite> get() = _favorites.value

    override fun observeFavorites(): Flow<List<Favorite>> = _favorites.asStateFlow()

    override fun observeIsFavorite(
        providerId: String,
        wallpaperId: String,
    ): Flow<Boolean> =
        _favorites.map { list ->
            list.any { it.wallpaper.providerId == providerId && it.wallpaper.id == wallpaperId }
        }

    override suspend fun toggleFavorite(wallpaper: Wallpaper) {
        val matches = { favorite: Favorite ->
            favorite.wallpaper.providerId == wallpaper.providerId && favorite.wallpaper.id == wallpaper.id
        }
        _favorites.update { current ->
            if (current.any(matches)) {
                current.filterNot(matches)
            } else {
                current + Favorite(wallpaper, addedAtMillis = System.currentTimeMillis())
            }
        }
    }

    /** Test hook: replaces the entire state in one shot. */
    fun setFavorites(favorites: List<Favorite>) {
        _favorites.value = favorites
    }
}

class FakeHistoryRepository : HistoryRepository {
    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val entries: List<HistoryEntry> get() = _entries.value

    override fun observeRecent(limit: Int): Flow<List<HistoryEntry>> = _entries.map { list -> list.take(limit) }

    override suspend fun record(
        wallpaper: Wallpaper,
        action: HistoryAction,
    ) {
        _entries.update { current ->
            current + HistoryEntry(wallpaper, action, atMillis = System.currentTimeMillis())
        }
    }

    override suspend fun clear() {
        _entries.value = emptyList()
    }

    /** Test hook: replaces the entire state in one shot. */
    fun setEntries(entries: List<HistoryEntry>) {
        _entries.value = entries
    }
}
