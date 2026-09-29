package com.cloudimage.feature.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudimage.core.data.repository.ApplyError
import com.cloudimage.core.data.repository.ApplyResult
import com.cloudimage.core.data.repository.ApplyTarget
import com.cloudimage.core.data.repository.DownloadsRepository
import com.cloudimage.core.data.repository.FavoritesRepository
import com.cloudimage.core.data.repository.HistoryRepository
import com.cloudimage.core.data.repository.SaveError
import com.cloudimage.core.data.repository.SaveResult
import com.cloudimage.core.data.repository.SourceCapability
import com.cloudimage.core.data.repository.WallpaperApplier
import com.cloudimage.core.data.repository.WallpaperSaver
import com.cloudimage.core.data.repository.WallpaperSources
import com.cloudimage.core.data.viewer.ViewerSession
import com.cloudimage.core.model.HistoryAction
import com.cloudimage.core.model.Wallpaper
import com.cloudimage.core.model.WallpaperDetails
import com.cloudimage.core.model.WallpaperQuery
import com.cloudimage.core.model.savedMimeType
import com.cloudimage.core.network.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
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

/**
 * Live byte progress of one gallery download (v1.0.22): the running count
 * and the total when the server stated one. [fraction] is null exactly when
 * the total is unknown — the button degrades to an indeterminate ring and
 * the label shows the running count alone.
 */
data class DownloadProgress(
    val bytesRead: Long,
    val totalBytes: Long?,
) {
    val fraction: Float?
        get() =
            totalBytes
                ?.takeIf { it > 0 }
                ?.let { (bytesRead.toDouble() / it).toFloat().coerceIn(0f, 1f) }
}

/** Immutable snapshot of everything the preview screen renders. */
data class DetailUiState(
    val wallpaper: Wallpaper? = null,
    val isFavorite: Boolean = false,
    val applyOp: OperationState = OperationState.Idle,
    val saveOp: OperationState = OperationState.Idle,
    val shareOp: OperationState = OperationState.Idle,
    /** Live bytes of an in-flight gallery download; null when idle. */
    val downloadProgress: DownloadProgress? = null,
    /** True the moment a wallpaper has ever been downloaded from the app —
     * the save button settles into a static, untappable checkmark. */
    val isDownloaded: Boolean = false,
    /** A previous image exists to page back to with a rightward swipe (v1.0.23). */
    val hasPrevious: Boolean = false,
    /** A next image exists to page forward to with a leftward swipe (v1.0.23). */
    val hasNext: Boolean = false,
    /**
     * Same-provider lookalikes over the wallpaper's top tags (v1.0.9) —
     * empty means the row stays hidden: the source does not declare
     * SEARCH + TAGS, the item carries no tags, or the query answered
     * nothing. A bonus row, never a complaint.
     */
    val moreLikeThis: List<Wallpaper> = emptyList(),
    /**
     * The source's definitive record (v1.0.21) — the TRUE resolution and
     * download size a listing could not carry, plus the author and the
     * page URL. Null until it arrives, and permanently when the listing
     * already knew the dimensions (no fetch happens — see
     * [loadDetails]) or the source had nothing truer to say; the info
     * sheet degrades to the grid item's own values either way.
     */
    val details: WallpaperDetails? = null,
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
 *
 * Since v1.0.23 the screen also pages in place: the grid it was opened from
 * parks its list in the [ViewerSession], which this ViewModel snapshots and
 * owns — sideways swipes then move through it, reloading history,
 * recommendations and details for each image that lands.
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
        private val downloadsRepository: DownloadsRepository,
        private val sources: WallpaperSources,
        private val viewerSession: ViewerSession,
    ) : ViewModel() {
        private val initialWallpaper = DetailDestination.decode(savedStateHandle[DetailDestination.arg])

        /** The list this viewer pages through — the grid it was opened from, or the lookalike fallback. */
        private var frame: ViewerSession.Frame? = null

        /** The per-wallpaper loads (recommendations, details), cancelled on every page. */
        private var loadsJob: Job? = null

        private val _state = MutableStateFlow(DetailUiState(wallpaper = initialWallpaper))
        val state: StateFlow<DetailUiState> = _state.asStateFlow()

        private val _events = MutableSharedFlow<DetailEvent>(extraBufferCapacity = 8)
        val events: SharedFlow<DetailEvent> = _events.asSharedFlow()

        init {
            initialWallpaper?.let { wallpaper ->
                frame = viewerSession.take(wallpaper)
                updateNeighbors()
                viewModelScope.launch { historyRepository.record(wallpaper, HistoryAction.VIEWED) }
                observeWallpaperFlags()
                loadsJob =
                    viewModelScope.launch {
                        loadMoreLikeThis(wallpaper)
                        loadDetails(wallpaper)
                    }
            }
        }

        /** Saves or removes the wallpaper from favorites. */
        fun onToggleFavorite() {
            val wallpaper = _state.value.wallpaper ?: return
            viewModelScope.launch { favoritesRepository.toggleFavorite(wallpaper) }
        }

        /**
         * Pages the viewer through its list (v1.0.23): +1 is the next
         * image (a leftward swipe), -1 the previous (a rightward one). The
         * wallpaper swaps in place — history, recommendations and details
         * reload for the image that lands while the loads belonging to the
         * one that leaves are cancelled; a page past either end lands on
         * the rubber band instead, not on a wrap-around.
         */
        fun onNavigate(delta: Int) {
            if (delta == 0) return
            val current = frame ?: return
            val target = current.index + delta
            if (target !in current.wallpapers.indices) return
            val wallpaper = current.wallpapers[target]
            frame = current.copy(index = target)
            _state.update {
                it.copy(
                    wallpaper = wallpaper,
                    details = null,
                    moreLikeThis = emptyList(),
                    isFavorite = false,
                    isDownloaded = false,
                    applyOp = OperationState.Idle,
                    saveOp = OperationState.Idle,
                    shareOp = OperationState.Idle,
                    downloadProgress = null,
                )
            }
            updateNeighbors()
            loadsJob?.cancel()
            loadsJob =
                viewModelScope.launch {
                    historyRepository.record(wallpaper, HistoryAction.VIEWED)
                    loadMoreLikeThis(wallpaper)
                    loadDetails(wallpaper)
                }
        }

        /**
         * Follows the wallpaper on screen through favorites and downloads:
         * the observers re-bind on every in-place page, so a swipe never
         * carries the previous image's flags over to the next one.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        private fun observeWallpaperFlags() {
            _state
                .map { it.wallpaper }
                .filterNotNull()
                .distinctUntilChanged()
                .flatMapLatest { wallpaper ->
                    combine(
                        favoritesRepository.observeIsFavorite(wallpaper.providerId, wallpaper.id),
                        downloadsRepository.observeIsDownloaded(wallpaper.providerId, wallpaper.id),
                    ) { isFavorite, isDownloaded -> isFavorite to isDownloaded }
                }.onEach { (isFavorite, isDownloaded) ->
                    _state.update { it.copy(isFavorite = isFavorite, isDownloaded = isDownloaded) }
                }.launchIn(viewModelScope)
        }

        /** Publishes which paging neighbors exist for the wallpaper on screen. */
        private fun updateNeighbors() {
            _state.update {
                it.copy(hasPrevious = frame?.hasPrevious == true, hasNext = frame?.hasNext == true)
            }
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

        /**
         * Saves the full-resolution image into the system gallery.
         *
         * While the bytes stream, [DetailUiState.downloadProgress] carries the
         * running count for the button's ring and size label. A successful save
         * records the download (the Library's Downloaded tab and this button's
         * checkmark both live off that record) next to the usual history entry;
         * a failure clears the progress and leaves the button idle — the retry
         * is a fresh tap.
         */
        fun onSave() {
            val wallpaper = _state.value.wallpaper ?: return
            if (_state.value.saveOp is OperationState.Running) return
            if (_state.value.isDownloaded) return
            viewModelScope.launch {
                _state.update { it.copy(saveOp = OperationState.Running, downloadProgress = null) }
                when (
                    val result =
                        saver.saveToGallery(wallpaper) { bytesRead, totalBytes ->
                            _state.update { it.copy(downloadProgress = DownloadProgress(bytesRead, totalBytes)) }
                        }
                ) {
                    is SaveResult.Success -> {
                        _state.update { it.copy(saveOp = OperationState.Succeeded, downloadProgress = null) }
                        historyRepository.record(wallpaper, HistoryAction.DOWNLOADED)
                        downloadsRepository.recordDownload(wallpaper)
                        _events.tryEmit(DetailEvent.WallpaperSaved)
                    }
                    is SaveResult.Failure -> {
                        val error = result.error.toDetailError()
                        _state.update { it.copy(saveOp = OperationState.Failed(error), downloadProgress = null) }
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
            // No grid parked a list for this screen (process death, a deep
            // link): the row itself becomes the browsing list, led by the
            // wallpaper on screen so "previous" from the first lookalike
            // steps back to it.
            if (frame == null && results.isNotEmpty()) {
                frame = ViewerSession.Frame(listOf(wallpaper) + results, index = 0)
                updateNeighbors()
            }
        }

        /**
         * Asks the source for the definitive record — but only when the
         * listing could not state the wallpaper's dimensions. Listings that
         * publish true dimensions (Wallhaven's API, WallpaperCave's topic
         * pages) have nothing to gain: one extra request per opened preview
         * would cost an API-keyed source real quota for a file size the
         * info sheet can live without. Listings that cannot — HDQWalls's
         * grid publishes only its uniform card crop — gain the file's TRUE
         * resolution, and with it the download size and the page URL when
         * the site states them. Every failure is silent: the info sheet
         * keeps the grid item's own values.
         */
        private suspend fun loadDetails(wallpaper: Wallpaper) {
            if (wallpaper.width != null && wallpaper.height != null) return
            sources.sources.filterNotNull().first()
            when (val outcome = sources.details(wallpaper)) {
                is NetworkResult.Failure -> Unit
                is NetworkResult.Success -> _state.update { it.copy(details = outcome.value) }
            }
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
