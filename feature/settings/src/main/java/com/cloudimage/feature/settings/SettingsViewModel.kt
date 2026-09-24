package com.cloudimage.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudimage.core.data.repository.FavoritesRepository
import com.cloudimage.core.data.repository.HistoryRepository
import com.cloudimage.core.datastore.UserPreferencesRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app version shown in the About section. An injected wrapper (resolved
 * through PackageManager) keeps the view model testable — a plain class on
 * purpose: @JvmInline value classes mangle @Provides names and trip KSP.
 */
class VersionName(
    val value: String,
)

/** Immutable snapshot of everything the settings screen renders. */
data class SettingsUiState(
    val sfwOnly: Boolean = true,
    val dynamicColorsEnabled: Boolean = true,
    val gridColumns: Int = 2,
    val favoriteCount: Int = 0,
    val historyCount: Int = 0,
    val versionName: String = "",
    /** True until the first combine emission lands. */
    val isLoading: Boolean = true,
)

/**
 * Drives the settings screen. Every change writes straight to the
 * preferences DataStore, and the new state streams back from the same
 * source — the UI never optimistically mutates itself.
 */
@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val userPreferencesRepository: UserPreferencesRepository,
        favoritesRepository: FavoritesRepository,
        historyRepository: HistoryRepository,
        versionName: VersionName,
    ) : ViewModel() {
        private val _state = MutableStateFlow(SettingsUiState(versionName = versionName.value))
        val state: StateFlow<SettingsUiState> = _state.asStateFlow()

        init {
            combine(
                userPreferencesRepository.preferences,
                favoritesRepository.observeFavorites(),
                historyRepository.observeRecent(limit = HISTORY_COUNT_CAP),
            ) { preferences, favorites, history ->
                SettingsUiState(
                    sfwOnly = preferences.sfwOnly,
                    dynamicColorsEnabled = preferences.dynamicColorsEnabled,
                    gridColumns = preferences.gridColumns,
                    favoriteCount = favorites.size,
                    historyCount = history.size,
                    versionName = versionName.value,
                    isLoading = false,
                )
            }.onEach { _state.value = it }
                .launchIn(viewModelScope)
        }

        fun setSfwOnly(enabled: Boolean) {
            viewModelScope.launch { userPreferencesRepository.setSfwOnly(enabled) }
        }

        fun setDynamicColorsEnabled(enabled: Boolean) {
            viewModelScope.launch { userPreferencesRepository.setDynamicColorsEnabled(enabled) }
        }

        fun setGridColumns(columns: Int) {
            viewModelScope.launch { userPreferencesRepository.setGridColumns(columns) }
        }

        private companion object {
            /** Enough for the info row; the library screen shows the real feed. */
            const val HISTORY_COUNT_CAP = 500
        }
    }

@Module
@InstallIn(SingletonComponent::class)
internal object SettingsModule {
    @Provides
    @Singleton
    fun provideVersionName(
        @ApplicationContext context: Context,
    ): VersionName =
        VersionName(
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: "unknown",
        )
}
