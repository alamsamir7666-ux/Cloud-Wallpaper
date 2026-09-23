package com.cloudimage.feature.extensions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudimage.core.data.repository.SourceInfo
import com.cloudimage.core.data.repository.WallpaperSources
import com.cloudimage.core.datastore.UserPreferencesRepository
import com.cloudimage.extensions.core.AddRepoResult
import com.cloudimage.extensions.core.ExtensionRepository
import com.cloudimage.extensions.core.InstallResult
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
    data class RepoAdded(val name: String) : ExtensionsEvent

    data class Installed(val name: String) : ExtensionsEvent

    data class InstallFailed(val reason: String) : ExtensionsEvent

    data class KeySaved(val provider: String) : ExtensionsEvent
}

/** Immutable snapshot of the extension manager screen. */
data class ExtensionsUiState(
    val loading: Boolean = true,
    val extensions: List<InstalledExtension> = emptyList(),
    val sources: List<SourceInfo> = emptyList(),
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
)

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
                preferences.providerApiKeys.collect { keys ->
                    _state.update { it.copy(keyedProviders = keys.keys) }
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
                _state.update { it.copy(installing = it.installing + entry.id, failed = it.failed - entry.id) }
                when (val result = repoManager.install(repo, entry)) {
                    is InstallResult.Installed -> {
                        _state.update { it.copy(installing = it.installing - entry.id) }
                        _events.emit(ExtensionsEvent.Installed(entry.id))
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

        private fun describe(error: com.cloudimage.extensions.core.ExtensionError): String =
            when (error) {
                is com.cloudimage.extensions.core.ExtensionError.ChecksumMismatch -> "checksum mismatch"
                is com.cloudimage.extensions.core.ExtensionError.UnsupportedApi -> "unsupported api ${error.declared}"
                is com.cloudimage.extensions.core.ExtensionError.InvalidManifest -> "invalid manifest"
                is com.cloudimage.extensions.core.ExtensionError.Io -> "network or storage failure"
                else -> "package could not be loaded"
            }
    }
