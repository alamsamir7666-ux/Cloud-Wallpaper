package com.cloudimage.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudimage.core.data.repository.FavoritesRepository
import com.cloudimage.core.data.repository.HistoryRepository
import com.cloudimage.core.datastore.UserPreferencesRepository
import com.cloudimage.core.model.Favorite
import com.cloudimage.core.model.HistoryEntry
import com.cloudimage.core.model.Wallpaper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Immutable snapshot of everything the library screen renders. */
data class LibraryUiState(
    val favorites: List<Favorite> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    val gridColumns: Int = 2,
    /** True until the first combine emission lands (Room is fast, but not
     *  synchronous) — guards tests and later loading UI. */
    val isLoading: Boolean = true,
) {
    /** Favorites deliberately ignore the SFW-only setting: the user already
     *  chose to keep these, and they were clamped at browse time. */
    val hasFavorites: Boolean get() = favorites.isNotEmpty()

    val hasHistory: Boolean get() = history.isNotEmpty()
}

/**
 * Drives the library tab: the saved favorites grid and the history feed.
 *
 * Both lists stream out of Room, so any change made elsewhere — a heart
 * tapped on the detail screen, a record written by the apply flow — lands
 * here live without manual refreshes.
 */
@HiltViewModel
class LibraryViewModel
    @Inject
    constructor(
        private val favoritesRepository: FavoritesRepository,
        private val historyRepository: HistoryRepository,
        userPreferencesRepository: UserPreferencesRepository,
    ) : ViewModel() {
        private val _state = MutableStateFlow(LibraryUiState())
        val state: StateFlow<LibraryUiState> = _state.asStateFlow()

        init {
            combine(
                favoritesRepository.observeFavorites(),
                historyRepository.observeRecent(),
                userPreferencesRepository.preferences,
            ) { favorites, history, preferences ->
                LibraryUiState(
                    favorites = favorites,
                    history = history,
                    gridColumns = preferences.gridColumns,
                    isLoading = false,
                )
            }.onEach { _state.value = it }
                .launchIn(viewModelScope)
        }

        /** Removes one wallpaper from the saved grid (toggle = remove here,
         *  because the button only appears on already-saved items). */
        fun removeFromFavorites(wallpaper: Wallpaper) {
            viewModelScope.launch { favoritesRepository.toggleFavorite(wallpaper) }
        }

        /** Wipes the history feed; favorites are untouched. */
        fun clearHistory() {
            viewModelScope.launch { historyRepository.clear() }
        }
    }
