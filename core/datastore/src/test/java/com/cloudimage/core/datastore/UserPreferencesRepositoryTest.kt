package com.cloudimage.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.turbine.test
import com.cloudimage.core.model.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UserPreferencesRepositoryTest {
    @get:Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder.builder().assureDeletion().build()

    @Test
    fun defaultsAreSfwAndDynamicColorsOn() =
        runTest {
            val repository = UserPreferencesRepository(newDataStore(backgroundScope))

            val preferences = repository.preferences.first()

            assertEquals(UserPreferences(sfwOnly = true, dynamicColorsEnabled = true, gridColumns = 2), preferences)
        }

    @Test
    fun settingSfwOnlyEmitsUpdatedPreferences() =
        runTest {
            val repository = UserPreferencesRepository(newDataStore(backgroundScope))

            repository.preferences.test {
                assertEquals(UserPreferences(), awaitItem())

                repository.setSfwOnly(false)

                assertEquals(UserPreferences(sfwOnly = false), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun gridColumnsAreClampedToValidRange() =
        runTest {
            val repository = UserPreferencesRepository(newDataStore(backgroundScope))

            repository.setGridColumns(columns = 99)

            assertEquals(4, repository.preferences.first().gridColumns)
        }

    @Test
    fun onboardingIsIncompleteByDefault() =
        runTest {
            val repository = UserPreferencesRepository(newDataStore(backgroundScope))

            assertFalse(repository.preferences.first().onboardingCompleted)
        }

    @Test
    fun completingOnboardingFlipsOnlyTheFlag() =
        runTest {
            val repository = UserPreferencesRepository(newDataStore(backgroundScope))
            repository.setSfwOnly(false)

            repository.setOnboardingCompleted()

            val preferences = repository.preferences.first()
            assertTrue(preferences.onboardingCompleted)
            assertFalse(preferences.sfwOnly)
        }

    private fun newDataStore(scope: CoroutineScope) =
        PreferenceDataStoreFactory.create(
            // Caller-provided scope: cancelled automatically when the test ends.
            scope = scope,
            produceFile = { tmpFolder.newFile("user_preferences_test.preferences_pb") },
        )
}
