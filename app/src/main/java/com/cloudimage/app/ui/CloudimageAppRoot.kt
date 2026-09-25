package com.cloudimage.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cloudimage.app.ui.onboarding.OnboardingScreen
import com.cloudimage.app.ui.theme.CloudimageTheme

/**
 * The root composable: owns the theme (dynamic colors follow preferences)
 * and picks between the first-run welcome flow and the main app scaffold.
 */
@Composable
fun CloudimageAppRoot(modifier: Modifier = Modifier) {
    val viewModel: AppViewModel = hiltViewModel()
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()

    CloudimageTheme(dynamicColor = preferences?.dynamicColorsEnabled ?: true) {
        val loaded = preferences
        when {
            // Still reading DataStore — the splash screen covers this window.
            loaded == null ->
                Box(
                    modifier =
                        modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                )

            !loaded.onboardingCompleted ->
                OnboardingScreen(onDone = viewModel::completeOnboarding)

            else -> CloudimageApp()
        }
    }
}
