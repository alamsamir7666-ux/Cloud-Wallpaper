package com.cloudimage.feature.extensions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudimage.core.datastore.UserPreferencesRepository
import com.cloudimage.extensions.core.AddRepoResult
import com.cloudimage.extensions.core.ExtensionManifest
import com.cloudimage.extensions.core.ExtensionRepository
import com.cloudimage.extensions.core.InstalledExtension
import com.cloudimage.extensions.core.RepoError
import com.cloudimage.extensions.core.RepoIndexResult
import com.cloudimage.extensions.core.RepoManager
import com.cloudimage.extensions.core.RepoPackageEntry
import com.cloudimage.extensions.core.StoredRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One-time messages for the snackbar. */
sealed interface ExtensionsEvent {
    data class RepoAdded(
        val name: String,
    ) : ExtensionsEvent
}

/** Which population a counts-bar segment represents (Cloudstream naming). */
enum class ExtensionStatKind {
    DOWNLOADED,
    DISABLED,
    NOT_DOWNLOADED,
}

/** One proportional counts-bar segment — [count] is always > 0. */
data class ExtensionStatSegment(
    val kind: ExtensionStatKind,
    val count: Int,
)

/**
 * Immutable snapshot of the repository browser — the extensions screen's
 * entry point (v1.0.20): the user's repositories plus the three counts
 * the bottom bar visualizes. Installed/catalog detail lives on the pushed
 * repo and installed screens; only the aggregates are needed here.
 */
data class ExtensionsUiState(
    val loading: Boolean = true,
    val repos: List<StoredRepo> = emptyList(),
    /** Catalogs by repo id; null while that repo's index is being fetched. */
    val catalogs: Map<String, List<RepoPackageEntry>?> = emptyMap(),
    val installed: List<InstalledExtension> = emptyList(),
    /** Source ids the user switched off. */
    val disabledSources: Set<String> = emptySet(),
    val addingRepo: Boolean = false,
    val repoError: RepoError? = null,
) {
    /** Installed manifests by id — broken rows (unreadable manifest) never appear. */
    val installedManifests: Map<String, ExtensionManifest>
        get() = installed.mapNotNull { it.manifest }.associateBy { it.id }

    /** Installed and switched on — Cloudstream's "Downloaded" count. */
    val downloadedCount: Int get() = installedManifests.count { it.key !in disabledSources }

    /** Installed but switched off. */
    val disabledCount: Int get() = installedManifests.count { it.key in disabledSources }

    /** Catalog entries not installed anywhere, distinct across all repos. */
    val notDownloadedCount: Int
        get() =
            catalogs.values
                .filterNotNull()
                .flatten()
                .distinctBy { it.id }
                .count { it.id !in installedManifests }

    /**
     * The counts bar's segments in draw order. Compose's `weight()` throws
     * on zero, so empty populations are simply absent — the proportional
     * row must never receive a zero weight (the shipped v1.0.9 bar did,
     * crashing the screen on entry for anyone without one of each
     * population). An empty list means the bar renders a neutral track.
     */
    fun statsSegments(): List<ExtensionStatSegment> =
        buildList {
            if (downloadedCount > 0) add(ExtensionStatSegment(ExtensionStatKind.DOWNLOADED, downloadedCount))
            if (disabledCount > 0) add(ExtensionStatSegment(ExtensionStatKind.DISABLED, disabledCount))
            if (notDownloadedCount > 0) add(ExtensionStatSegment(ExtensionStatKind.NOT_DOWNLOADED, notDownloadedCount))
        }
}

/**
 * Drives the repository browser: the user's repositories, adding and
 * removing them, and the three counts the bottom bar renders from.
 */
@HiltViewModel
class ExtensionsViewModel
    @Inject
    constructor(
        private val repository: ExtensionRepository,
        private val repoManager: RepoManager,
        private val preferences: UserPreferencesRepository,
    ) : ViewModel() {
        private val _state = MutableStateFlow(ExtensionsUiState())
        val state: StateFlow<ExtensionsUiState> = _state.asStateFlow()

        private val _events = MutableSharedFlow<ExtensionsEvent>()
        val events: SharedFlow<ExtensionsEvent> = _events.asSharedFlow()

        init {
            viewModelScope.launch {
                repository.installed.collect { extensions ->
                    _state.update {
                        it.copy(loading = extensions == null, installed = extensions.orEmpty())
                    }
                }
            }
            viewModelScope.launch {
                preferences.disabledSources.collect { disabled ->
                    _state.update { it.copy(disabledSources = disabled) }
                }
            }
            refresh()
        }

        /** Re-reads the repo list and re-fetches every catalog for the counts. */
        fun refresh() {
            viewModelScope.launch {
                repository.refresh()
                _state.update { it.copy(repos = repoManager.repos()) }
                repoManager.repos().forEach { loadCatalog(it) }
            }
        }

        fun addRepo(url: String) {
            if (_state.value.addingRepo) return
            viewModelScope.launch {
                _state.update { it.copy(addingRepo = true, repoError = null) }
                when (val result = repoManager.add(url)) {
                    is AddRepoResult.Added -> {
                        _state.update { it.copy(addingRepo = false, repos = repoManager.repos()) }
                        loadCatalog(result.repo)
                        _events.emit(ExtensionsEvent.RepoAdded(result.repo.name))
                    }
                    is AddRepoResult.Failed ->
                        _state.update { it.copy(addingRepo = false, repoError = result.error) }
                }
            }
        }

        fun removeRepo(repo: StoredRepo) {
            viewModelScope.launch {
                repoManager.remove(repo.id)
                _state.update { it.copy(repos = repoManager.repos(), catalogs = it.catalogs - repo.id) }
            }
        }

        fun clearRepoError() {
            _state.update { it.copy(repoError = null) }
        }

        private suspend fun loadCatalog(repo: StoredRepo) {
            _state.update { it.copy(catalogs = it.catalogs + (repo.id to null)) }
            when (val result = repoManager.catalog(repo)) {
                is RepoIndexResult.Ok ->
                    _state.update { it.copy(catalogs = it.catalogs + (repo.id to result.index.packages)) }
                is RepoIndexResult.Failed ->
                    _state.update { it.copy(catalogs = it.catalogs + (repo.id to emptyList())) }
            }
        }
    }
