package com.cloudimage.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import com.cloudimage.core.model.RotationSettings
import com.cloudimage.core.model.RotationTarget
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

    // ---- Wallpaper auto-rotation (v1.0.3) ----

    @Test
    fun rotationIsDisabledByDefault() =
        runTest {
            val repository = UserPreferencesRepository(newDataStore(backgroundScope))

            assertEquals(RotationSettings(), repository.preferences.first().rotation)
        }

    @Test
    fun rotationKnobsWriteAndFlow() =
        runTest {
            val repository = UserPreferencesRepository(newDataStore(backgroundScope))

            repository.preferences.test {
                assertEquals(RotationSettings(), awaitItem().rotation)

                repository.setRotationEnabled(true)
                assertEquals(RotationSettings(enabled = true), awaitItem().rotation)

                repository.setRotationInterval(1440)
                assertEquals(RotationSettings(enabled = true, intervalMinutes = 1440), awaitItem().rotation)

                repository.setRotationWifiOnly(true)
                assertEquals(
                    RotationSettings(enabled = true, intervalMinutes = 1440, wifiOnly = true),
                    awaitItem().rotation,
                )

                repository.setRotationTarget(RotationTarget.BOTH)
                assertEquals(
                    RotationSettings(enabled = true, intervalMinutes = 1440, wifiOnly = true, target = RotationTarget.BOTH),
                    awaitItem().rotation,
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun unofferedIntervalsFallBackToTheDefault() =
        runTest {
            val repository = UserPreferencesRepository(newDataStore(backgroundScope))

            repository.setRotationInterval(7)

            assertEquals(
                RotationSettings.DEFAULT_INTERVAL_MINUTES,
                repository.preferences
                    .first()
                    .rotation.intervalMinutes,
            )
        }

    @Test
    fun unknownStoredTargetNamesDegradeToHome() =
        runTest {
            val dataStore = newDataStore(backgroundScope)
            val repository = UserPreferencesRepository(dataStore)
            dataStore.edit { it[stringPreferencesKey("rotation_target")] = "SIDEWAYS" }

            assertEquals(
                RotationTarget.HOME,
                repository.preferences
                    .first()
                    .rotation.target,
            )
        }

    @Test
    fun theRotationCursorPersists() =
        runTest {
            val repository = UserPreferencesRepository(newDataStore(backgroundScope))

            repository.setLastRotationKey("wallhaven/e1abc2")

            assertEquals("wallhaven/e1abc2", repository.lastRotationKey.first())
        }

    // ---- Muzei source (v1.0.4) ----

    @Test
    fun muzeiCursorStartsAtZeroAndPersists() =
        runTest {
            val repository = UserPreferencesRepository(newDataStore(backgroundScope))

            assertEquals(0, repository.muzeiCursor.first())

            repository.setMuzeiCursor(41)

            assertEquals(41, repository.muzeiCursor.first())
        }

    @Test
    fun muzeiFeedPageStartsAtOneAndNeverStoresBelowIt() =
        runTest {
            val repository = UserPreferencesRepository(newDataStore(backgroundScope))

            assertEquals(1, repository.muzeiFeedPage.first())

            repository.setMuzeiFeedPage(0)

            assertEquals(1, repository.muzeiFeedPage.first())
        }

    // ---- Browse feed source pinning (v1.0.6) ----

    @Test
    fun browseSourceSelectionStartsUnpinnedPersistsAndClears() =
        runTest {
            val repository = UserPreferencesRepository(newDataStore(backgroundScope))

            assertEquals("", repository.preferences.first().browseSourceId)

            repository.setBrowseSourceId("unsplash")
            assertEquals("unsplash", repository.preferences.first().browseSourceId)

            repository.setBrowseSourceId(null)
            assertEquals("", repository.preferences.first().browseSourceId)
        }

    // ---- Search history (v1.0.9) ----

    @Test
    fun searchHistoryStartsEmptyAndRecordsMostRecentFirst() =
        runTest {
            val repository = UserPreferencesRepository(newDataStore(backgroundScope))

            assertTrue(repository.searchHistory.first().isEmpty())

            repository.addSearchQuery("nature")
            repository.addSearchQuery("space")

            assertEquals(listOf("space", "nature"), repository.searchHistory.first())
        }

    @Test
    fun recordingTheSameSearchMovesItToTheFrontWithoutDuplicates() =
        runTest {
            val repository = UserPreferencesRepository(newDataStore(backgroundScope))
            repository.addSearchQuery("nature")
            repository.addSearchQuery("space")

            repository.addSearchQuery("nature")

            assertEquals(listOf("nature", "space"), repository.searchHistory.first())
        }

    @Test
    fun searchHistoryCapsAtTenDroppingTheOldest() =
        runTest {
            val repository = UserPreferencesRepository(newDataStore(backgroundScope))

            (1..12).forEach { repository.addSearchQuery("query-$it") }

            assertEquals(
                (12 downTo 3).map { "query-$it" },
                repository.searchHistory.first(),
            )
        }

    @Test
    fun blankAndWhitespaceSearchesAreNeverRecorded() =
        runTest {
            val repository = UserPreferencesRepository(newDataStore(backgroundScope))

            repository.addSearchQuery("   ")
            repository.addSearchQuery("")

            assertTrue(repository.searchHistory.first().isEmpty())
        }

    @Test
    fun recordedSearchesAreTrimmedAndCollapsedToOneLine() =
        runTest {
            val repository = UserPreferencesRepository(newDataStore(backgroundScope))

            repository.addSearchQuery("  mountain\tlake  ")

            assertEquals(listOf("mountain lake"), repository.searchHistory.first())
        }

    @Test
    fun clearingSearchHistoryRemovesEverything() =
        runTest {
            val repository = UserPreferencesRepository(newDataStore(backgroundScope))
            repository.addSearchQuery("nature")
            repository.addSearchQuery("space")

            repository.clearSearchHistory()

            assertTrue(repository.searchHistory.first().isEmpty())
        }

    private fun newDataStore(scope: CoroutineScope) =
        PreferenceDataStoreFactory.create(
            // Caller-provided scope: cancelled automatically when the test ends.
            scope = scope,
            produceFile = { tmpFolder.newFile("user_preferences_test.preferences_pb") },
        )
}
