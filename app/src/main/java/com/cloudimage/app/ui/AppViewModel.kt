package com.cloudimage.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudimage.core.datastore.UserPreferencesRepository
import com.cloudimage.core.model.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * App-shell state: decides between the first-run onboarding and the main
 * scaffold, and keeps the theme's dynamic-color switch in sync with
 * preferences.
 *
 * [preferences] is null only until DataStore's first emission arrives —
 * the splash screen covers that window.
 */
@HiltViewModel
class AppViewModel
    @Inject
    constructor(
        private val userPreferencesRepository: UserPreferencesRepository,
    ) : ViewModel() {
        val preferences: StateFlow<UserPreferences?> =
            userPreferencesRepository.preferences
                .stateIn(viewModelScope, SharingStarted.Eagerly, null)

        /** Finishes the welcome flow, applying the content-safety choice. */
        fun completeOnboarding(sfwOnly: Boolean) {
            viewModelScope.launch {
                userPreferencesRepository.setSfwOnly(sfwOnly)
                userPreferencesRepository.setOnboardingCompleted()
            }
        }
    }
