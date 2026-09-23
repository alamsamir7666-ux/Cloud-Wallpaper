package com.cloudimage.feature.extensions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudimage.extensions.core.ExtensionRepository
import com.cloudimage.extensions.core.InstalledExtension
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State holder for the extension manager.
 *
 * The engine exposes `installed` as null-until-refreshed, which maps to the
 * loading flag here — a cold start shows a spinner, not a misleading
 * "nothing installed" state.
 */
@HiltViewModel
class ExtensionsViewModel
    @Inject
    constructor(
        private val repository: ExtensionRepository,
    ) : ViewModel() {
        data class UiState(
            val loading: Boolean = true,
            val extensions: List<InstalledExtension> = emptyList(),
        )

        private val _state = MutableStateFlow(UiState())
        val state: StateFlow<UiState> = _state.asStateFlow()

        init {
            viewModelScope.launch {
                repository.installed.collect { extensions ->
                    _state.update {
                        it.copy(loading = extensions == null, extensions = extensions.orEmpty())
                    }
                }
            }
            refresh()
        }

        fun refresh() {
            viewModelScope.launch { repository.refresh() }
        }

        fun uninstall(extension: InstalledExtension) {
            viewModelScope.launch { repository.uninstall(extension.id) }
        }
    }
