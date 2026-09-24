package com.cloudimage.app.ui

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.cloudimage.core.datastore.UserPreferencesRepository
import com.cloudimage.core.model.UserPreferences
import com.cloudimage.core.testing.MainDispatcherRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder.builder().assureDeletion().build()

    @Test
    fun preferencesStartNullThenEmitDefaults() =
        runTest {
            val preferences = newPreferences()

            val viewModel = AppViewModel(preferences)

            assertNull(viewModel.preferences.value)
            assertEquals(UserPreferences(), viewModel.preferences.first { it != null })
        }

    @Test
    fun completingOnboardingPersistsTheSfwChoice() =
        runTest {
            val preferences = newPreferences()
            val viewModel = AppViewModel(preferences)

            viewModel.completeOnboarding(sfwOnly = false)

            val result = preferences.preferences.first { it.onboardingCompleted }
            assertEquals(false, result.sfwOnly)
        }

    @Test
    fun completingOnboardingKeepsTheDefaultSfwChoice() =
        runTest {
            val preferences = newPreferences()
            val viewModel = AppViewModel(preferences)

            viewModel.completeOnboarding(sfwOnly = true)

            val result = preferences.preferences.first { it.onboardingCompleted }
            assertEquals(true, result.sfwOnly)
        }

    private fun TestScope.newPreferences(): UserPreferencesRepository =
        UserPreferencesRepository(
            PreferenceDataStoreFactory.create(scope = backgroundScope) {
                tmpFolder.newFile("preferences_${System.nanoTime()}.preferences_pb")
            },
        )
}
