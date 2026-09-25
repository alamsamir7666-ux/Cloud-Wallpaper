package com.cloudimage.feature.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudimage.core.data.repository.ApplyError
import com.cloudimage.core.data.repository.ApplyResult
import com.cloudimage.core.data.repository.ApplyTarget
import com.cloudimage.core.data.repository.FavoritesRepository
import com.cloudimage.core.data.repository.HistoryRepository
import com.cloudimage.core.data.repository.SaveError
import com.cloudimage.core.data.repository.SaveResult
import com.cloudimage.core.data.repository.SourceCapability
import com.cloudimage.core.data.repository.WallpaperApplier
import com.cloudimage.core.data.repository.WallpaperSaver
import com.cloudimage.core.data.repository.WallpaperSources
import com.cloudimage.core.model.HistoryAction
import com.cloudimage.core.model.Wallpaper
import com.cloudimage.core.model.WallpaperQuery
import com.cloudimage.core.model.savedMimeType
import com.cloudimage.core.network.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** User-facing error taxonomy for the preview screen. */
enum class DetailError {
    OFFLINE,
    TIMEOUT,
    HTTP,
    BAD_IMAGE,
    UNSUPPORTED,
    STORAGE,
}

/** Which action a failure message refers to. */
enum class DetailAction {
    APPLY,
    SAVE,
    SHARE,
}

/** Lifecycle of one user-triggered operation (apply / save / share). */
sealed interface OperationState {
    data object Idle : OperationState

    data object Running : OperationState

    data object Succeeded : OperationState

    data class Failed(
        val error: DetailError,
    ) : OperationState
}

/** One-shot events the screen consumes (snackbars, share intents). */
sealed interface DetailEvent {
    data object WallpaperApplied : DetailEvent

    data object WallpaperSaved : DetailEvent

    data class ShareReady(
        val filePath: String,
        val mimeType: String,
    ) : DetailEvent

    data class ActionFailed(
        val action: DetailAction,
        val error: DetailError,
    ) : DetailEvent
}

/** Immutable snapshot of everything the preview screen renders. */
data class DetailUiState(
    val wallpaper: Wallpaper? = null,
    val isFavorite: Boolean = false,
    val applyOp: OperationState = OperationState.Idle,
    val saveOp: OperationState = OperationState.Idle,
    val shareOp: OperationState = OperationState.Idle,
    /**
     * Same-provider lookalikes over the wallpaper's top tags (v1.0.9) —
     * empty means the row stays hidden: the source does not declare
     * SEARCH + TAGS, the item carries no tags, or the query answered
     * nothing. A bonus row, never a complaint.
     */
    val moreLikeThis: List<Wallpaper> = emptyList(),
)

/**
 * Drives the fullscreen preview: favorite toggle, set-as-wallpaper
 * (home/lock/both), save-to-gallery and share. Opening the screen records a
 * VIEWED history entry; successful applies and saves are recorded as well.
 *
 * When the wallpaper's source declares SEARCH + TAGS and the item carries
 * tags, a same-provider "More like this" carousel loads alongside (v1.0.9):
 * the top tags become one query, broadening to the single strongest tag
 * when the combination was too narrow.
 */
@HiltViewModel
class DetailViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val applier: WallpaperApplier,
        private val saver: WallpaperSaver,
        private val favoritesRepository: FavoritesRepository,
        private val historyRepository: HistoryRepository,
        private val sources: WallpaperSources,
    ) : ViewModel() {
        private val initialWallpaper = DetailDestination.decode(savedStateHandle[DetailDestination.arg])

        private val _state = MutableStateFlow(DetailUiState(wallpaper = initialWallpaper))
        val state: StateFlow<DetailUiState> = _state.asStateFlow()

        private val _events = MutableSharedFlow<DetailEvent>(extraBufferCapacity = 8)
        val events: SharedFlow<DetailEvent> = _events.asSharedFlow()

        init {
            initialWallpaper?.let { wallpaper ->
                viewModelScope.launch { historyRepository.record(wallpaper, HistoryAction.VIEWED) }
                favoritesRepository
                    .observeIsFavorite(wallpaper.providerId, wallpaper.id)
                    .onEach { isFavorite -> _state.update { it.copy(isFavorite = isFavorite) } }
                    .launchIn(viewModelScope)
                viewModelScope.launch { loadMoreLikeThis(wallpaper) }
            }
        }

        /** Saves or removes the wallpaper from favorites. */
        fun onToggleFavorite() {
            val wallpaper = _state.value.wallpaper ?: return
            viewModelScope.launch { favoritesRepository.toggleFavorite(wallpaper) }
        }

        /** Applies the wallpaper to the chosen target screen(s). */
        fun onApply(target: ApplyTarget) {
            val wallpaper = _state.value.wallpaper ?: return
            if (_state.value.applyOp is OperationState.Running) return
            viewModelScope.launch {
                _state.update { it.copy(applyOp = OperationState.Running) }
                when (val result = applier.apply(wallpaper, target)) {
                    is ApplyResult.Success -> {
                        _state.update { it.copy(applyOp = OperationState.Succeeded) }
                        historyRepository.record(wallpaper, HistoryAction.APPLIED)
                        _events.tryEmit(DetailEvent.WallpaperApplied)
                    }
                    is ApplyResult.Failure -> {
                        val error = result.error.toDetailError()
                        _state.update { it.copy(applyOp = OperationState.Failed(error)) }
                        _events.tryEmit(DetailEvent.ActionFailed(DetailAction.APPLY, error))
                    }
                }
            }
        }

        /** Saves the full-resolution image into the system gallery. */
        fun onSave() {
            val wallpaper = _state.value.wallpaper ?: return
            if (_state.value.saveOp is OperationState.Running) return
            viewModelScope.launch {
                _state.update { it.copy(saveOp = OperationState.Running) }
                when (val result = saver.saveToGallery(wallpaper)) {
                    is SaveResult.Success -> {
                        _state.update { it.copy(saveOp = OperationState.Succeeded) }
                        historyRepository.record(wallpaper, HistoryAction.DOWNLOADED)
                        _events.tryEmit(DetailEvent.WallpaperSaved)
                    }
                    is SaveResult.Failure -> {
                        val error = result.error.toDetailError()
                        _state.update { it.copy(saveOp = OperationState.Failed(error)) }
                        _events.tryEmit(DetailEvent.ActionFailed(DetailAction.SAVE, error))
                    }
                }
            }
        }

        /** Stages a cache file for sharing; the screen fires the intent. */
        fun onShare() {
            val wallpaper = _state.value.wallpaper ?: return
            if (_state.value.shareOp is OperationState.Running) return
            viewModelScope.launch {
                _state.update { it.copy(shareOp = OperationState.Running) }
                when (val result = saver.prepareShareFile(wallpaper)) {
                    is SaveResult.Success -> {
                        _state.update { it.copy(shareOp = OperationState.Succeeded) }
                        _events.tryEmit(DetailEvent.ShareReady(result.uri, wallpaper.savedMimeType()))
                    }
                    is SaveResult.Failure -> {
                        val error = result.error.toDetailError()
                        _state.update { it.copy(shareOp = OperationState.Failed(error)) }
                        _events.tryEmit(DetailEvent.ActionFailed(DetailAction.SHARE, error))
                    }
                }
            }
        }

        /**
         * Waits for source discovery, then gates the row honestly: the
         * wallpaper's own source must declare SEARCH + TAGS (disabled
         * sources never appear in the list, so they simply offer nothing)
         * and the item must carry at least one usable tag. Only then does
         * the recommendation query run.
         */
        private suspend fun loadMoreLikeThis(wallpaper: Wallpaper) {
            val available = sources.sources.filterNotNull().first()
            val info = available.firstOrNull { it.id == wallpaper.providerId } ?: return
            val canRecommend =
                SourceCapability.SEARCH in info.capabilities && SourceCapability.TAGS in info.capabilities
            if (!canRecommend || wallpaper.tags.none { it.isNotBlank() }) return
            val results = searchMoreLikeThis(wallpaper) ?: return
            _state.update { it.copy(moreLikeThis = results) }
        }

        /**
         * The same-provider tag query with one broadening step: the top
         * [MORE_LIKE_THIS_TAG_LIMIT] tags together first (precision — a
         * wallpaper tagged forest, mist, mountain should look like all
         * three), then the single strongest tag when that combination
         * matched nothing (recall). Any source failure returns null and the
         * row stays hidden — recommendations degrade silently.
         */
        private suspend fun searchMoreLikeThis(wallpaper: Wallpaper): List<Wallpaper>? {
            val topTags = wallpaper.tags.filter { it.isNotBlank() }.take(MORE_LIKE_THIS_TAG_LIMIT)
            if (topTags.isEmpty()) return null
            val attempts =
                buildList {
                    add(topTags)
                    if (topTags.size > 1) add(listOf(topTags.first()))
                }
            for (tags in attempts) {
                when (
                    val outcome =
                        sources.search(
                            WallpaperQuery(text = tags.joinToString(" ")),
                            page = 1,
                            sourceId = wallpaper.providerId,
                        )
                ) {
                    is NetworkResult.Failure -> return null
                    is NetworkResult.Success -> {
                        val results =
                            outcome.value.page.wallpapers
                                .filter { it.id != wallpaper.id }
                                .distinctBy { it.id }
                                .take(MORE_LIKE_THIS_LIMIT)
                        if (results.isNotEmpty()) return results
                        // Empty on the combined tags — fall through to the
                        // single-tag attempt when one exists.
                    }
                }
            }
            return emptyList()
        }

        private fun ApplyError.toDetailError(): DetailError =
            when (this) {
                ApplyError.OFFLINE -> DetailError.OFFLINE
                ApplyError.TIMEOUT -> DetailError.TIMEOUT
                ApplyError.HTTP -> DetailError.HTTP
                ApplyError.DECODE -> DetailError.BAD_IMAGE
                ApplyError.UNSUPPORTED -> DetailError.UNSUPPORTED
                ApplyError.IO -> DetailError.STORAGE
            }

        private fun SaveError.toDetailError(): DetailError =
            when (this) {
                SaveError.OFFLINE -> DetailError.OFFLINE
                SaveError.TIMEOUT -> DetailError.TIMEOUT
                SaveError.HTTP -> DetailError.HTTP
                SaveError.IO -> DetailError.STORAGE
            }

        private companion object {
            /** How many of the wallpaper's tags join the recommendation query. */
            const val MORE_LIKE_THIS_TAG_LIMIT = 3

            /** How many lookalikes the carousel shows at most. */
            const val MORE_LIKE_THIS_LIMIT = 15
        }
    }
