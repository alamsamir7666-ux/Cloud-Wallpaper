package com.cloudimage.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudimage.core.data.repository.AppUpdateRepository
import com.cloudimage.core.data.repository.ApplyError
import com.cloudimage.core.data.repository.FavoritesRepository
import com.cloudimage.core.data.repository.HistoryRepository
import com.cloudimage.core.data.repository.UpdateInstallResult
import com.cloudimage.core.data.repository.UpdateInstaller
import com.cloudimage.core.data.repository.isVersionNewer
import com.cloudimage.core.data.rotation.RotationResult
import com.cloudimage.core.data.rotation.WallpaperRotator
import com.cloudimage.core.datastore.UserPreferencesRepository
import com.cloudimage.core.model.AppUpdate
import com.cloudimage.core.model.RotationSettings
import com.cloudimage.core.model.RotationTarget
import com.cloudimage.core.model.Wallpaper
import com.cloudimage.core.network.NetworkError
import com.cloudimage.core.network.NetworkResult
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
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
    /** The in-app updater card. */
    val update: UpdateState = UpdateState.Idle,
    /** Wallpaper auto-rotation knobs (v1.0.3). */
    val rotation: RotationSettings = RotationSettings(),
    /** Feedback of the last explicit "rotate now" tap. */
    val rotateNow: RotateNowState = RotateNowState.Idle,
)

/** What the "rotate now" row shows beneath its button. */
sealed interface RotateNowState {
    /** Never tapped (or a new rotation just started). */
    data object Idle : RotateNowState

    data object Running : RotateNowState

    /** A wallpaper was picked and applied. */
    data class Done(
        val wallpaper: Wallpaper,
    ) : RotateNowState

    /** Nothing is saved; the row explains how to fix that. */
    data object NoFavorites : RotateNowState

    data class Failed(
        val error: ApplyError,
    ) : RotateNowState
}

/** What the update section shows. */
sealed interface UpdateState {
    /** Never checked (screen just opened). */
    data object Idle : UpdateState

    data object Checking : UpdateState

    /** Checked: the running build is the newest release. */
    data object UpToDate : UpdateState

    /** A newer release APK is ready to download. */
    data class Available(
        val update: AppUpdate,
    ) : UpdateState

    /** The APK is being downloaded; the installer opens when it lands. */
    data object Downloading : UpdateState

    /** Check or install failed; [error] drives the message. */
    data class Failed(
        val error: UpdateError,
    ) : UpdateState
}

/** User-facing failure taxonomy for the update flow. */
enum class UpdateError {
    HTTP,
    TIMEOUT,
    OFFLINE,
    BAD_DATA,

    /** The APK downloaded but the system installer did not come up. */
    INSTALL,
}

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
        private val appUpdateRepository: AppUpdateRepository,
        private val updateInstaller: UpdateInstaller,
        private val rotator: WallpaperRotator,
        favoritesRepository: FavoritesRepository,
        historyRepository: HistoryRepository,
        versionName: VersionName,
        /** The application-lifetime scope: a "rotate now" must finish even if the user leaves settings. */
        private val rotationScope: CoroutineScope,
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
                    rotation = preferences.rotation,
                    isLoading = false,
                )
            }.onEach { value -> _state.update { value.copy(update = it.update, rotateNow = it.rotateNow) } }
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

        // ---- Wallpaper auto-rotation (v1.0.3) ----

        fun setRotationEnabled(enabled: Boolean) {
            viewModelScope.launch { userPreferencesRepository.setRotationEnabled(enabled) }
        }

        fun setRotationInterval(minutes: Int) {
            viewModelScope.launch { userPreferencesRepository.setRotationInterval(minutes) }
        }

        fun setRotationWifiOnly(wifiOnly: Boolean) {
            viewModelScope.launch { userPreferencesRepository.setRotationWifiOnly(wifiOnly) }
        }

        fun setRotationTarget(target: RotationTarget) {
            viewModelScope.launch { userPreferencesRepository.setRotationTarget(target) }
        }

        /**
         * Applies the next saved wallpaper immediately, outside the periodic
         * cadence. Runs on the application scope — leaving settings mid-apply
         * must not cancel a wallpaper that is already being downloaded and
         * set.
         */
        fun rotateNow() {
            if (_state.value.rotateNow is RotateNowState.Running) return
            _state.update { it.copy(rotateNow = RotateNowState.Running) }
            rotationScope.launch {
                val target =
                    userPreferencesRepository.preferences
                        .first()
                        .rotation.target
                when (val result = rotator.rotateOnce(target)) {
                    is RotationResult.Success -> _state.update { it.copy(rotateNow = RotateNowState.Done(result.wallpaper)) }

                    RotationResult.NoWallpapers -> _state.update { it.copy(rotateNow = RotateNowState.NoFavorites) }

                    is RotationResult.ApplyFailed -> _state.update { it.copy(rotateNow = RotateNowState.Failed(result.error)) }
                }
            }
        }

        /**
         * Reads the latest GitHub release and compares it with the running
         * build. Re-checks are ignored while one is in flight (or while a
         * download is running) so double-taps stay harmless.
         */
        fun checkForUpdate() {
            val current = _state.value.update
            if (current is UpdateState.Checking || current is UpdateState.Downloading) return
            viewModelScope.launch {
                _state.update { it.copy(update = UpdateState.Checking) }
                when (val result = appUpdateRepository.latest()) {
                    is NetworkResult.Success -> {
                        val update = result.value
                        val isNewer = isVersionNewer(remote = update.versionName, current = _state.value.versionName)
                        _state.update {
                            it.copy(update = if (isNewer) UpdateState.Available(update) else UpdateState.UpToDate)
                        }
                    }

                    is NetworkResult.Failure ->
                        _state.update { it.copy(update = UpdateState.Failed(result.error.toUpdateError())) }
                }
            }
        }

        /** Downloads the release APK and hands it to the system installer. */
        fun downloadAndInstall() {
            val update = (state.value.update as? UpdateState.Available)?.update ?: return
            viewModelScope.launch {
                _state.update { it.copy(update = UpdateState.Downloading) }
                when (val result = updateInstaller.downloadAndInstall(update)) {
                    is UpdateInstallResult.Started ->
                        // The user may cancel the system installer; keep the
                        // card actionable so they can come back to it.
                        _state.update { it.copy(update = UpdateState.Available(update)) }

                    is UpdateInstallResult.Failure ->
                        _state.update { it.copy(update = UpdateState.Failed(UpdateError.INSTALL)) }
                }
            }
        }

        private fun NetworkError.toUpdateError(): UpdateError =
            when (this) {
                is NetworkError.Http -> UpdateError.HTTP
                NetworkError.Timeout -> UpdateError.TIMEOUT
                is NetworkError.Io -> UpdateError.OFFLINE
                is NetworkError.Serialization -> UpdateError.BAD_DATA
                // The update check talks to GitHub Releases directly — no
                // plugin involved; kept for exhaustiveness.
                is NetworkError.Source -> UpdateError.HTTP
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
