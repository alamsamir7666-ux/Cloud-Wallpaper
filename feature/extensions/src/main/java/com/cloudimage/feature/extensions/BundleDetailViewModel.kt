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

/** Catalog fetch outcome for the bundle screen — a vanished bundle is not a network failure. */
enum class BundleCatalogStatus {
    LOADING,

    READY,

    /** The repo answered, but its index no longer declares this bundle. */
    MISSING,

    FAILED,
}

/**
 * Immutable snapshot of one bundle's detail screen (v1.2.2): the resolved
 * members in bundle order, the ids the catalog no longer carries, the
 * per-member install state, and the checkbox selection driving the bulk
 * install button.
 */
data class BundleDetailUiState(
    val repo: StoredRepo? = null,
    val bundle: RepoBundleEntry? = null,
    /** The decoded route argument — the title fallback before the catalog answers. */
    val requestedBundleId: String = "",
    val status: BundleCatalogStatus = BundleCatalogStatus.LOADING,
    /** Bundle members resolved against the catalog, in bundle order. */
    val entries: List<RepoPackageEntry> = emptyList(),
    /** Bundle-declared ids the catalog no longer carries — rendered as inert rows. */
    val missingIds: List<String> = emptyList(),
    val installed: List<InstalledExtension> = emptyList(),
    /** Member ids checked for the bulk install; pre-selects the not-yet-installed. */
    val selected: Set<String> = emptySet(),
    /** Member ids with an install in flight. */
    val installing: Set<String> = emptySet(),
    /** Member ids that failed their last install attempt. */
    val failedInstalls: Set<String> = emptySet(),
) {
    /** Installed manifests by id — broken rows (unreadable manifest) never appear. */
    val installedManifests: Map<String, ExtensionManifest>
        get() = installed.mapNotNull { it.manifest }.associateBy { it.id }

    /** Author-declared size, missing members included. */
    val totalMembers: Int
        get() = entries.size + missingIds.size

    /** Installed members right now — the "M of N installed" signal. */
    val installedCount: Int
        get() = entries.count { it.id in installedManifests }

    /** True when nothing is left to install and nothing is missing. */
    val complete: Boolean
        get() = entries.isNotEmpty() && missingIds.isEmpty() && entries.all { it.id in installedManifests }

    /**
     * True when [entry] advertises something other than what is installed —
     * the same rule as the catalog screen's update affordance.
     */
    fun updateAvailable(entry: RepoPackageEntry): Boolean {
        val installedManifest = installedManifests[entry.id] ?: return false
        return entry.versionCode > installedManifest.versionCode ||
            (entry.versionCode == installedManifest.versionCode && entry.versionName != installedManifest.versionName)
    }
}

/**
 * Drives one bundle's detail screen (v1.2.2): resolves the bundle against
 * the repo's catalog, tracks the checkbox selection, installs the checked
 * members one-by-one through the same sha256-verified per-package path as
 * the catalog screen (a walking spinner; one failure never aborts the
 * rest), and uninstalls individual members straight from their rows.
 *
 * Bundle installs are sequential by design: extension packages are small,
 * one row at a time reads as honest progress, and a cancelled batch (the
 * user navigating away mid-install) leaves only fully-installed members
 * behind — the next visit re-derives the truth from disk.
 */
@HiltViewModel
class BundleDetailViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val repoManager: RepoManager,
        private val repository: ExtensionRepository,
    ) : ViewModel() {
        private val repoId: String =
            ExtensionsDestination.decodeRepoId(savedStateHandle.get<String>(ExtensionsDestination.repoArg).orEmpty())

        private val bundleId: String =
            ExtensionsDestination.decodeRepoId(savedStateHandle.get<String>(ExtensionsDestination.bundleArg).orEmpty())

        private val _state = MutableStateFlow(BundleDetailUiState(requestedBundleId = bundleId))
        val state: StateFlow<BundleDetailUiState> = _state.asStateFlow()

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
                repo?.let { loadCatalog(it) } ?: _state.update { it.copy(status = BundleCatalogStatus.FAILED) }
            }
        }

        /** Re-fetches the catalog — the "check for updates" affordance. */
        fun refresh() {
            val repo = _state.value.repo ?: return
            if (_state.value.status == BundleCatalogStatus.LOADING) return
            viewModelScope.launch { loadCatalog(repo) }
        }

        /** Flips one checkbox; frozen while an install batch is in flight. */
        fun toggleSelected(entryId: String) {
            if (_state.value.installing.isNotEmpty()) return
            _state.update {
                it.copy(selected = if (entryId in it.selected) it.selected - entryId else it.selected + entryId)
            }
        }

        /**
         * Installs every checked member sequentially. Members that succeed
         * drop out of the selection; members that fail keep their red row
         * and their single-row retry. One summary event at the end.
         */
        fun installSelected() {
            val repo = _state.value.repo ?: return
            if (_state.value.installing.isNotEmpty()) return
            val targets = _state.value.entries.filter { it.id in _state.value.selected }
            if (targets.isEmpty()) return
            viewModelScope.launch {
                var installedCount = 0
                var failedCount = 0
                for (entry in targets) {
                    _state.update {
                        it.copy(installing = it.installing + entry.id, failedInstalls = it.failedInstalls - entry.id)
                    }
                    val result = repoManager.install(repo, entry)
                    val ok = result is InstallResult.Installed
                    if (ok) installedCount++ else failedCount++
                    _state.update {
                        it.copy(
                            installing = it.installing - entry.id,
                            selected = it.selected - entry.id,
                            failedInstalls = if (ok) it.failedInstalls else it.failedInstalls + entry.id,
                        )
                    }
                }
                _events.emit(
                    RepoDetailEvent.BundleInstalled(
                        bundleName = _state.value.bundle?.let(::displayBundleName) ?: bundleId,
                        installedCount = installedCount,
                        failedCount = failedCount,
                    ),
                )
            }
        }

        /** Installs one member on its own — the row's download affordance. */
        fun installMember(entry: RepoPackageEntry) {
            val repo = _state.value.repo ?: return
            if (entry.id in _state.value.installing) return
            viewModelScope.launch {
                // Install-over vs fresh install decides the snackbar verb.
                val wasInstalled = entry.id in _state.value.installedManifests
                _state.update { it.copy(installing = it.installing + entry.id, failedInstalls = it.failedInstalls - entry.id) }
                when (val result = repoManager.install(repo, entry)) {
                    is InstallResult.Installed -> {
                        _state.update { it.copy(installing = it.installing - entry.id, selected = it.selected - entry.id) }
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

        /** Deletes an installed member straight from its row. */
        fun uninstallMember(entry: RepoPackageEntry) {
            if (entry.id !in _state.value.installedManifests) return
            viewModelScope.launch {
                repository.uninstall(entry.id)
                _events.emit(RepoDetailEvent.Uninstalled(displayName(entry)))
            }
        }

        private suspend fun loadCatalog(repo: StoredRepo) {
            _state.update { it.copy(status = BundleCatalogStatus.LOADING) }
            when (val result = repoManager.catalog(repo)) {
                is RepoIndexResult.Ok -> {
                    val bundle = result.index.bundles.firstOrNull { it.id == bundleId }
                    if (bundle == null) {
                        _state.update {
                            it.copy(
                                status = BundleCatalogStatus.MISSING,
                                entries = emptyList(),
                                missingIds = emptyList(),
                                selected = emptySet(),
                            )
                        }
                        return
                    }
                    val byId = result.index.packages.associateBy { it.id }
                    val memberIds = bundle.packageIds.distinct()
                    val members = memberIds.mapNotNull { byId[it] }
                    val missing = memberIds.filter { it !in byId }
                    _state.update { current ->
                        val installedIds = current.installedManifests.keys
                        current.copy(
                            status = BundleCatalogStatus.READY,
                            bundle = bundle,
                            entries = members,
                            missingIds = missing,
                            // Pre-check exactly the members a one-tap install would add.
                            selected = members.filter { it.id !in installedIds }.map { it.id }.toSet(),
                            failedInstalls = emptySet(),
                        )
                    }
                }
                is RepoIndexResult.Failed ->
                    _state.update {
                        it.copy(
                            status = BundleCatalogStatus.FAILED,
                            entries = emptyList(),
                            missingIds = emptyList(),
                        )
                    }
            }
        }

        private fun describe(error: ExtensionError): String = error.reason
    }
