package com.cloudimage.feature.extensions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudimage.core.data.repository.SourceInfo
import com.cloudimage.core.data.repository.WallpaperSources
import com.cloudimage.core.datastore.UserPreferencesRepository
import com.cloudimage.extensions.core.AddRepoResult
import com.cloudimage.extensions.core.ExtensionManifest
import com.cloudimage.extensions.core.ExtensionRepository
import com.cloudimage.extensions.core.InstallResult
import com.cloudimage.extensions.core.InstalledExtension
import com.cloudimage.extensions.core.RepoError
import com.cloudimage.extensions.core.RepoIndexResult
import com.cloudimage.extensions.core.RepoManager
import com.cloudimage.extensions.core.RepoPackageEntry
import com.cloudimage.extensions.core.StoredRepo
import com.cloudimage.extensions.core.reason
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One-time messages for the snackbar. */
sealed interface ExtensionsEvent {
    data class RepoAdded(
        val name: String,
    ) : ExtensionsEvent

    data class Installed(
        val name: String,
    ) : ExtensionsEvent

    data class Updated(
        val name: String,
    ) : ExtensionsEvent

    data class InstallFailed(
        val reason: String,
    ) : ExtensionsEvent

    data class KeySaved(
        val provider: String,
    ) : ExtensionsEvent
}

/** Which population a stats-bar segment represents. */
enum class ExtensionStatKind {
    ENABLED,
    DISABLED,
    AVAILABLE,
}

/** One proportional stats-bar segment — [count] is always > 0. */
data class ExtensionStatSegment(
    val kind: ExtensionStatKind,
    val count: Int,
)

/**
 * Immutable snapshot of the extension manager screen.
 *
 * The v1.0.9 Part 3 additions are [disabledSources], [query] and the
 * derived helpers under them — the proportional stats bar, the update
 * detection and the catalog search all read from this state without a
 * second round-trip through the ViewModel.
 */
data class ExtensionsUiState(
    val loading: Boolean = true,
    val extensions: List<InstalledExtension> = emptyList(),
    val sources: List<SourceInfo> = emptyList(),
    /** Load-failure reasons by source id — per-source diagnostics. */
    val loadFailures: Map<String, String> = emptyMap(),
    val repos: List<StoredRepo> = emptyList(),
    /** Catalogs by repo id; null while that repo's index is being fetched. */
    val catalogs: Map<String, List<RepoPackageEntry>?> = emptyMap(),
    /** Entry ids with an install in flight. */
    val installing: Set<String> = emptySet(),
    /** Entry ids that failed the last install attempt. */
    val failed: Set<String> = emptySet(),
    /** Keyed provider ids that have a stored API key. */
    val keyedProviders: Set<String> = emptySet(),
    val addingRepo: Boolean = false,
    val repoError: RepoError? = null,
    /** Source ids the user switched off (v1.0.9 Part 3). */
    val disabledSources: Set<String> = emptySet(),
    /** The catalog search text; blank shows every catalog row (v1.0.9 Part 3). */
    val query: String = "",
) {
    /** Installed manifests by id — broken rows (unreadable manifest) never appear. */
    val installedManifests: Map<String, ExtensionManifest>
        get() = extensions.mapNotNull { it.manifest }.associateBy { it.id }

    /** Rows whose source participates in the feed. */
    val enabledCount: Int get() = installedManifests.count { it.key !in disabledSources }

    /** Rows the user switched off — installed, but out of every query. */
    val disabledCount: Int get() = installedManifests.count { it.key in disabledSources }

    /** Catalog entries not installed anywhere, distinct across all repos. */
    val availableCount: Int
        get() =
            catalogs.values
                .filterNotNull()
                .flatten()
                .distinctBy { it.id }
                .count { it.id !in installedManifests }

    /**
     * The stats bar's segments in draw order. Compose's `weight()` throws
     * on zero, so empty populations are simply absent — the proportional
     * row must never receive a zero weight (the shipped v1.0.9 bar did,
     * crashing the screen on entry for anyone without one of each
     * population). An empty list means the bar renders nothing at all.
     */
    fun statsSegments(): List<ExtensionStatSegment> =
        buildList {
            if (enabledCount > 0) add(ExtensionStatSegment(ExtensionStatKind.ENABLED, enabledCount))
            if (disabledCount > 0) add(ExtensionStatSegment(ExtensionStatKind.DISABLED, disabledCount))
            if (availableCount > 0) add(ExtensionStatSegment(ExtensionStatKind.AVAILABLE, availableCount))
        }

    /**
     * True when [entry] advertises something other than what is
     * installed: a higher code wins outright, and an equal code with a
     * different name (a re-publish under the same code) still offers the
     * action. Whether the package actually replaces anything stays the
     * engine's business — its sha256 + version gates decide at install
     * time; this is only the label on the button.
     */
    fun updateAvailable(entry: RepoPackageEntry): Boolean {
        val installed = installedManifests[entry.id] ?: return false
        return entry.versionCode > installed.versionCode ||
            (entry.versionCode == installed.versionCode && entry.versionName != installed.versionName)
    }

    /**
     * The catalog search filter: blank shows everything, non-blank keeps
     * entries whose id contains the text (case-insensitive) — "unsplash"
     * finds "cloudimage.unsplash" without knowing the reverse-DNS prefix.
     */
    fun catalogMatches(entry: RepoPackageEntry): Boolean = query.isBlank() || entry.id.contains(query, ignoreCase = true)
}

/**
 * Drives the extension manager: installed extensions, the user's
 * repositories and their catalogs, installs from either, and the per
 * provider API keys key-based sources demand.
 */
@HiltViewModel
class ExtensionsViewModel
    @Inject
    constructor(
        private val repository: ExtensionRepository,
        private val repoManager: RepoManager,
        private val preferences: UserPreferencesRepository,
        private val sources: WallpaperSources,
    ) : ViewModel() {
        private val _state = MutableStateFlow(ExtensionsUiState())
        val state: StateFlow<ExtensionsUiState> = _state.asStateFlow()

        private val _events = MutableSharedFlow<ExtensionsEvent>()
        val events: SharedFlow<ExtensionsEvent> = _events.asSharedFlow()

        init {
            viewModelScope.launch {
                repository.installed.collect { extensions ->
                    _state.update {
                        it.copy(loading = extensions == null, extensions = extensions.orEmpty())
                    }
                }
            }
            viewModelScope.launch {
                sources.sources.collect { available ->
                    _state.update { it.copy(sources = available.orEmpty()) }
                }
            }
            viewModelScope.launch {
                sources.loadFailures.collect { failures ->
                    _state.update { it.copy(loadFailures = failures) }
                }
            }
            viewModelScope.launch {
                preferences.providerApiKeys.collect { keys ->
                    _state.update { it.copy(keyedProviders = keys.keys) }
                }
            }
            viewModelScope.launch {
                preferences.disabledSources.collect { disabled ->
                    _state.update { it.copy(disabledSources = disabled) }
                }
            }
            refresh()
        }

        fun refresh() {
            viewModelScope.launch {
                repository.refresh()
                _state.update { it.copy(repos = repoManager.repos()) }
                repoManager.repos().forEach { loadCatalog(it) }
            }
        }

        fun uninstall(extension: InstalledExtension) {
            viewModelScope.launch { repository.uninstall(extension.id) }
        }

        /**
         * Switches one installed source on or off (v1.0.9 Part 3). A
         * disable also clears the browse pin when it names this source —
         * the pin must never dead-end the browse screen behind the
         * manager's back.
         */
        fun setSourceEnabled(
            extension: InstalledExtension,
            enabled: Boolean,
        ) {
            val id = extension.manifest?.id ?: return
            viewModelScope.launch {
                if (!enabled && preferences.preferences.first().browseSourceId == id) {
                    preferences.setBrowseSourceId(null)
                }
                preferences.setSourceEnabled(id, enabled)
            }
        }

        /** Re-fetches one repo's catalog — the "check for updates" affordance. */
        fun refreshRepo(repo: StoredRepo) {
            // Null is the in-flight marker set by loadCatalog — a second
            // tap on a fetching repo must not queue a duplicate fetch.
            if (_state.value.catalogs[repo.id] == null) return
            viewModelScope.launch { loadCatalog(repo) }
        }

        /** The catalog search text; blank shows every catalog row. */
        fun setQuery(text: String) {
            _state.update { it.copy(query = text) }
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

        fun installPackage(
            repo: StoredRepo,
            entry: RepoPackageEntry,
        ) {
            if (entry.id in _state.value.installing) return
            viewModelScope.launch {
                // Install-over vs fresh install decides the snackbar verb.
                val wasInstalled = entry.id in _state.value.installedManifests
                _state.update { it.copy(installing = it.installing + entry.id, failed = it.failed - entry.id) }
                when (val result = repoManager.install(repo, entry)) {
                    is InstallResult.Installed -> {
                        _state.update { it.copy(installing = it.installing - entry.id) }
                        _events.emit(
                            if (wasInstalled) {
                                ExtensionsEvent.Updated(entry.id)
                            } else {
                                ExtensionsEvent.Installed(entry.id)
                            },
                        )
                    }
                    is InstallResult.Failed -> {
                        _state.update { it.copy(installing = it.installing - entry.id, failed = it.failed + entry.id) }
                        _events.emit(ExtensionsEvent.InstallFailed(describe(result.error)))
                    }
                }
            }
        }

        fun saveApiKey(
            providerId: String,
            key: String,
        ) {
            viewModelScope.launch {
                preferences.setProviderApiKey(providerId, key)
                _events.emit(ExtensionsEvent.KeySaved(providerId))
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

        private fun describe(error: com.cloudimage.extensions.core.ExtensionError): String = error.reason
    }
