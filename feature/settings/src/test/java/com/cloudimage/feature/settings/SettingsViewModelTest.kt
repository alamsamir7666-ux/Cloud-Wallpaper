package com.cloudimage.feature.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.cloudimage.core.datastore.UserPreferencesRepository
import com.cloudimage.core.model.Favorite
import com.cloudimage.core.model.HistoryAction
import com.cloudimage.core.model.HistoryEntry
import com.cloudimage.core.model.Wallpaper
import com.cloudimage.core.testing.FakeFavoritesRepository
import com.cloudimage.core.testing.FakeHistoryRepository
import com.cloudimage.core.testing.MainDispatcherRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder.builder().assureDeletion().build()

    @Test
    fun initialStateMirrorsRepositoriesAndPreferences() =
        runTest {
            val favorites = FakeFavoritesRepository()
            val history = FakeHistoryRepository()
            favorites.setFavorites(
                listOf(Favorite(wallpaper(id = "a"), addedAtMillis = 1), Favorite(wallpaper(id = "b"), addedAtMillis = 2)),
            )
            history.setEntries(
                listOf(
                    HistoryEntry(wallpaper(id = "c"), HistoryAction.VIEWED, atMillis = 3),
                    HistoryEntry(wallpaper(id = "d"), HistoryAction.APPLIED, atMillis = 4),
                    HistoryEntry(wallpaper(id = "e"), HistoryAction.DOWNLOADED, atMillis = 5),
                ),
            )
            val preferences = newPreferences()

            val viewModel =
                newViewModel(
                    favorites = favorites,
                    history = history,
                    preferences = preferences,
                )

            val state = viewModel.state.first { !it.isLoading }
            assertEquals(2, state.favoriteCount)
            assertEquals(3, state.historyCount)
            assertTrue(state.sfwOnly)
            assertTrue(state.dynamicColorsEnabled)
            assertEquals(2, state.gridColumns)
            assertEquals("1.2.3", state.versionName)
        }

    @Test
    fun togglingSfwWritesThroughTheDataStore() =
        runTest {
            val preferences = newPreferences()
            val viewModel = newViewModel(preferences = preferences)

            viewModel.setSfwOnly(false)

            assertEquals(false, preferences.preferences.first().sfwOnly)
        }

    @Test
    fun togglingDynamicColorsWritesThroughTheDataStore() =
        runTest {
            val preferences = newPreferences()
            val viewModel = newViewModel(preferences = preferences)

            viewModel.setDynamicColorsEnabled(false)

            assertEquals(false, preferences.preferences.first().dynamicColorsEnabled)
        }

    @Test
    fun settingGridColumnsClampsOutOfRangeValues() =
        runTest {
            val preferences = newPreferences()
            val viewModel = newViewModel(preferences = preferences)

            viewModel.setGridColumns(columns = 99)

            assertEquals(4, preferences.preferences.first().gridColumns)
        }

    @Test
    fun repositoryChangesUpdateTheCounts() =
        runTest {
            val favorites = FakeFavoritesRepository()
            val viewModel = newViewModel(favorites = favorites)

            favorites.toggleFavorite(wallpaper(id = "late"))

            assertEquals(1, viewModel.state.first { it.favoriteCount == 1 }.favoriteCount)
        }

    private fun TestScope.newViewModel(
        favorites: FakeFavoritesRepository = FakeFavoritesRepository(),
        history: FakeHistoryRepository = FakeHistoryRepository(),
        preferences: UserPreferencesRepository = newPreferences(),
    ): SettingsViewModel = SettingsViewModel(preferences, favorites, history, VersionName("1.2.3"))

    private fun TestScope.newPreferences(): UserPreferencesRepository =
        UserPreferencesRepository(
            PreferenceDataStoreFactory.create(scope = backgroundScope) {
                tmpFolder.newFile("preferences_${System.nanoTime()}.preferences_pb")
            },
        )

    private fun wallpaper(id: String) =
        Wallpaper(
            id = id,
            providerId = "test.provider",
            thumbUrl = "https://example.com/$id-thumb.jpg",
            fullUrl = "https://example.com/$id.jpg",
        )
}
