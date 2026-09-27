package com.cloudimage.feature.extensions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudimage.core.data.repository.SourceInfo
import com.cloudimage.core.data.repository.WallpaperSources
import com.cloudimage.core.datastore.UserPreferencesRepository
import com.cloudimage.extensions.core.ExtensionRepository
import com.cloudimage.extensions.core.InstalledExtension
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
sealed interface InstalledExtensionsEvent {
    data class KeySaved(
        val provider: String,
    ) : InstalledExtensionsEvent
}

/**
 * Immutable snapshot of the installed-extensions screen: every installed
 * extension with its per-source diagnostics, plus the search filter.
 */
data class InstalledExtensionsUiState(
    val loading: Boolean = true,
    val extensions: List<InstalledExtension> = emptyList(),
    val sources: List<SourceInfo> = emptyList(),
    /** Load-failure reasons by source id — per-source diagnostics. */
    val loadFailures: Map<String, String> = emptyMap(),
    /** Source ids the user switched off. */
    val disabledSources: Set<String> = emptySet(),
    /** Keyed provider ids that have a stored API key. */
    val keyedProviders: Set<String> = emptySet(),
    val query: String = "",
) {
    /** The visible rows: search-filtered, installed-first ordering preserved. */
    fun visibleExtensions(): List<InstalledExtension> =
        extensions.filter { extension ->
            query.isBlank() ||
                (extension.manifest?.name ?: extension.id).contains(query, ignoreCase = true) ||
                extension.id.contains(query, ignoreCase = true)
        }
}

/**
 * Drives the installed-extensions screen (v1.0.20): per-source enablement
 * switches, API keys for key-based sources, uninstall, and search.
 */
@HiltViewModel
class InstalledExtensionsViewModel
    @Inject
    constructor(
        private val repository: ExtensionRepository,
        private val preferences: UserPreferencesRepository,
        private val sources: WallpaperSources,
    ) : ViewModel() {
        private val _state = MutableStateFlow(InstalledExtensionsUiState())
        val state: StateFlow<InstalledExtensionsUiState> = _state.asStateFlow()

        private val _events = MutableSharedFlow<InstalledExtensionsEvent>()
        val events: SharedFlow<InstalledExtensionsEvent> = _events.asSharedFlow()

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
        }

        /**
         * Switches one installed source on or off. A disable also clears the
         * browse pin when it names this source — the pin must never
         * dead-end the browse screen behind this list's back.
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

        fun uninstall(extension: InstalledExtension) {
            viewModelScope.launch { repository.uninstall(extension.id) }
        }

        fun saveApiKey(
            providerId: String,
            key: String,
        ) {
            viewModelScope.launch {
                preferences.setProviderApiKey(providerId, key)
                _events.emit(InstalledExtensionsEvent.KeySaved(providerId))
            }
        }

        /** The search text; blank shows every installed extension. */
        fun setQuery(text: String) {
            _state.update { it.copy(query = text) }
        }
    }
