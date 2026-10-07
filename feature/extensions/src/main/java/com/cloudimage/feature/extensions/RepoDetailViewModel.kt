package com.cloudimage.feature.extensions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudimage.extensions.core.ExtensionError
import com.cloudimage.extensions.core.ExtensionManifest
import com.cloudimage.extensions.core.ExtensionRepository
import com.cloudimage.extensions.core.InstallResult
import com.cloudimage.extensions.core.InstalledExtension
import com.cloudimage.extensions.core.RepoBundleEntry
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One-time messages for the repo detail snackbar. */
sealed interface RepoDetailEvent {
    data class Installed(
        val name: String,
    ) : RepoDetailEvent

    data class Updated(
        val name: String,
    ) : RepoDetailEvent

    data class InstallFailed(
        val reason: String,
    ) : RepoDetailEvent

    data class Uninstalled(
        val name: String,
    ) : RepoDetailEvent

    /**
     * A bulk bundle install finished (v1.2.2) — one snackbar summarizing
     * the batch, not one per member. [failedCount] is the members that
     * keep their red row and single-row retry.
     */
    data class BundleInstalled(
        val bundleName: String,
        val installedCount: Int,
        val failedCount: Int,
    ) : RepoDetailEvent
}

/** The catalog fetch outcome. */
enum class RepoCatalogStatus {
    LOADING,
    READY,
    FAILED,
}

/**
 * Immutable snapshot of one repository's catalog screen: the repo, its
 * bundles (v1.2.2) and (search- and category-filtered) entries, and the
 * per-entry install state needed to render download / delete / update
 * affordances.
 */
data class RepoDetailUiState(
    val repo: StoredRepo? = null,
    val catalogStatus: RepoCatalogStatus = RepoCatalogStatus.LOADING,
    val entries: List<RepoPackageEntry> = emptyList(),
    /** Bundles the repo's index advertises; empty for older repos. */
    val bundles: List<RepoBundleEntry> = emptyList(),
    val installed: List<InstalledExtension> = emptyList(),
    val query: String = "",
    /** null = the All chip; otherwise a lower-case category from [categories]. */
    val category: String? = null,
    /** Entry ids with an install in flight. */
    val installing: Set<String> = emptySet(),
    /** Entry ids that failed the last install attempt. */
    val failedInstalls: Set<String> = emptySet(),
) {
    /** Installed manifests by id — broken rows (unreadable manifest) never appear. */
    val installedManifests: Map<String, ExtensionManifest>
        get() = installed.mapNotNull { it.manifest }.associateBy { it.id }

    /** Distinct lower-case categories across the catalog, alphabetized. */
    val categories: List<String>
        get() = entries.flatMap { it.categories }.distinct().sorted()

    /**
     * How many of the bundle's members are installed right now — the
     * "M of N installed" signal on the repo screen's bundle cards.
     */
    fun installedCount(bundle: RepoBundleEntry): Int = bundle.packageIds.count { it in installedManifests }

    /**
     * True when [entry] advertises something other than what is installed:
     * a higher code wins outright, and an equal code with a different
     * name (a re-publish under the same code) still offers the action.
     */
    fun updateAvailable(entry: RepoPackageEntry): Boolean {
        val installedManifest = installedManifests[entry.id] ?: return false
        return entry.versionCode > installedManifest.versionCode ||
            (entry.versionCode == installedManifest.versionCode && entry.versionName != installedManifest.versionName)
    }

    /** The visible rows: category- and search-filtered, sorted by name. */
    fun visibleEntries(): List<RepoPackageEntry> =
        entries
            .filter { entry ->
                (category == null || category in entry.categories) && matchesQuery(entry)
            }.sortedBy { displayName(it).lowercase() }

    /**
     * The visible bundle cards. Bundles carry no categories of their own,
     * so they ride above the category chips; they do honor the search
     * field, matching id, name, or description.
     */
    fun visibleBundles(): List<RepoBundleEntry> =
        bundles.filter { bundle ->
            query.isBlank() ||
                bundle.id.contains(query, ignoreCase = true) ||
                bundle.name.contains(query, ignoreCase = true) ||
                bundle.description.contains(query, ignoreCase = true)
        }

    private fun matchesQuery(entry: RepoPackageEntry): Boolean {
        if (query.isBlank()) return true
        return entry.id.contains(query, ignoreCase = true) ||
            displayName(entry).contains(query, ignoreCase = true) ||
            entry.description.contains(query, ignoreCase = true)
    }
}

/** The display name of a catalog entry, id-derived when the index is old. */
fun displayName(entry: RepoPackageEntry): String =
    entry.name.ifBlank {
        entry.id.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }

/** The display name of a bundle, id-derived when the author left it blank. */
fun displayBundleName(bundle: RepoBundleEntry): String = bundle.name.ifBlank { bundle.id.replaceFirstChar { it.uppercase() } }

/**
 * Drives one repository's catalog screen: fetch (and re-fetch) its index,
 * filter it, and install / uninstall individual entries.
 */
@HiltViewModel
class RepoDetailViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val repoManager: RepoManager,
        private val repository: ExtensionRepository,
    ) : ViewModel() {
        private val repoId: String =
            ExtensionsDestination.decodeRepoId(savedStateHandle.get<String>(ExtensionsDestination.repoArg).orEmpty())

        private val _state = MutableStateFlow(RepoDetailUiState())
        val state: StateFlow<RepoDetailUiState> = _state.asStateFlow()

        private val _events = MutableSharedFlow<RepoDetailEvent>()
        val events: SharedFlow<RepoDetailEvent> = _events.asSharedFlow()

        init {
            viewModelScope.launch {
                repository.installed.collect { extensions ->
                    _state.update { it.copy(installed = extensions.orEmpty()) }
                }
            }
            viewModelScope.launch {
                val repo = repoManager.repos().firstOrNull { it.id == repoId }
                _state.update { it.copy(repo = repo) }
                repo?.let { loadCatalog(it) } ?: _state.update { it.copy(catalogStatus = RepoCatalogStatus.FAILED) }
            }
        }

        /** Re-fetches the catalog — the "check for updates" affordance. */
        fun refresh() {
            val repo = _state.value.repo ?: return
            if (_state.value.catalogStatus == RepoCatalogStatus.LOADING) return
            viewModelScope.launch { loadCatalog(repo) }
        }

        /** The catalog search text; blank shows every entry. */
        fun setQuery(text: String) {
            _state.update { it.copy(query = text) }
        }

        /** null selects the All chip. A category that vanished re-selects All. */
        fun setCategory(category: String?) {
            _state.update { it.copy(category = category?.lowercase()) }
        }

        fun installPackage(entry: RepoPackageEntry) {
            val repo = _state.value.repo ?: return
            if (entry.id in _state.value.installing) return
            viewModelScope.launch {
                // Install-over vs fresh install decides the snackbar verb.
                val wasInstalled = entry.id in _state.value.installedManifests
                _state.update { it.copy(installing = it.installing + entry.id, failedInstalls = it.failedInstalls - entry.id) }
                when (val result = repoManager.install(repo, entry)) {
                    is InstallResult.Installed -> {
                        _state.update { it.copy(installing = it.installing - entry.id) }
                        _events.emit(
                            if (wasInstalled) {
                                RepoDetailEvent.Updated(displayName(entry))
                            } else {
                                RepoDetailEvent.Installed(displayName(entry))
                            },
                        )
                    }
                    is InstallResult.Failed -> {
                        _state.update { it.copy(installing = it.installing - entry.id, failedInstalls = it.failedInstalls + entry.id) }
                        _events.emit(RepoDetailEvent.InstallFailed(describe(result.error)))
                    }
                }
            }
        }

        /** Deletes an installed entry straight from the catalog row. */
        fun uninstallPackage(entry: RepoPackageEntry) {
            if (entry.id !in _state.value.installedManifests) return
            viewModelScope.launch {
                repository.uninstall(entry.id)
                _events.emit(RepoDetailEvent.Uninstalled(displayName(entry)))
            }
        }

        private suspend fun loadCatalog(repo: StoredRepo) {
            _state.update { it.copy(catalogStatus = RepoCatalogStatus.LOADING) }
            when (val result = repoManager.catalog(repo)) {
                is RepoIndexResult.Ok ->
                    _state.update {
                        it.copy(catalogStatus = RepoCatalogStatus.READY, entries = result.index.packages, bundles = result.index.bundles)
                    }
                is RepoIndexResult.Failed ->
                    _state.update { it.copy(catalogStatus = RepoCatalogStatus.FAILED, entries = emptyList(), bundles = emptyList()) }
            }
        }

        private fun describe(error: ExtensionError): String = error.reason
    }
