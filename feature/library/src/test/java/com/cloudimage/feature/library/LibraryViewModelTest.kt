package com.cloudimage.feature.library

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.cloudimage.core.datastore.UserPreferencesRepository
import com.cloudimage.core.model.Downloaded
import com.cloudimage.core.model.Favorite
import com.cloudimage.core.model.HistoryAction
import com.cloudimage.core.model.HistoryEntry
import com.cloudimage.core.model.Wallpaper
import com.cloudimage.core.testing.FakeDownloadsRepository
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

class LibraryViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder.builder().assureDeletion().build()

    @Test
    fun initialStateReflectsRepositoriesAndPreferences() =
        runTest {
            val favorites = FakeFavoritesRepository()
            val history = FakeHistoryRepository()
            val downloads = FakeDownloadsRepository()
            favorites.setFavorites(listOf(Favorite(wallpaper(id = "f1"), addedAtMillis = 10)))
            history.setEntries(
                listOf(HistoryEntry(wallpaper(id = "h1"), HistoryAction.VIEWED, atMillis = 20)),
            )
            downloads.setDownloads(listOf(Downloaded(wallpaper(id = "d1"), downloadedAtMillis = 30)))
            val preferences = newPreferences()

            val viewModel = LibraryViewModel(favorites, history, downloads, preferences)

            val state = viewModel.state.first { !it.isLoading }
            assertEquals(listOf("f1"), state.favorites.map { it.wallpaper.id })
            assertEquals(listOf("h1"), state.history.map { it.wallpaper.id })
            assertEquals(listOf("d1"), state.downloads.map { it.wallpaper.id })
            assertEquals(2, state.gridColumns)
        }

    @Test
    fun gridColumnsFollowThePreference() =
        runTest {
            val preferences = newPreferences()
            preferences.setGridColumns(columns = 3)
            val viewModel = newViewModel(preferences = preferences)

            assertEquals(3, viewModel.state.first { !it.isLoading }.gridColumns)
        }

    @Test
    fun favoriteChangesStreamIntoTheState() =
        runTest {
            val favorites = FakeFavoritesRepository()
            val viewModel = newViewModel(favorites = favorites)

            favorites.toggleFavorite(wallpaper(id = "new"))

            assertEquals(
                listOf("new"),
                viewModel.state
                    .first { it.favorites.isNotEmpty() }
                    .favorites
                    .map { it.wallpaper.id },
            )
        }

    @Test
    fun historyChangesStreamIntoTheState() =
        runTest {
            val history = FakeHistoryRepository()
            val viewModel = newViewModel(history = history)

            history.record(wallpaper(id = "w1"), HistoryAction.APPLIED)

            assertEquals(
                listOf("w1"),
                viewModel.state
                    .first { it.history.isNotEmpty() }
                    .history
                    .map { it.wallpaper.id },
            )
        }

    @Test
    fun removeFromFavoritesTogglesTheSavedWallpaperOff() =
        runTest {
            val favorites = FakeFavoritesRepository()
            val saved = wallpaper(id = "saved")
            favorites.setFavorites(listOf(Favorite(saved, addedAtMillis = 1)))
            val viewModel = newViewModel(favorites = favorites)

            viewModel.removeFromFavorites(saved)

            assertTrue(
                viewModel.state
                    .first { !it.isLoading && it.favorites.isEmpty() }
                    .favorites
                    .isEmpty(),
            )
        }

    @Test
    fun clearHistoryEmptiesOnlyTheHistoryFeed() =
        runTest {
            val favorites = FakeFavoritesRepository()
            val history = FakeHistoryRepository()
            favorites.setFavorites(listOf(Favorite(wallpaper(id = "kept"), addedAtMillis = 1)))
            history.setEntries(
                listOf(HistoryEntry(wallpaper(id = "gone"), HistoryAction.VIEWED, atMillis = 2)),
            )
            val viewModel = newViewModel(favorites = favorites, history = history)

            viewModel.clearHistory()

            val state = viewModel.state.first { !it.isLoading && it.history.isEmpty() }
            assertTrue(state.history.isEmpty())
            assertEquals(listOf("kept"), state.favorites.map { it.wallpaper.id })
        }

    @Test
    fun downloadChangesStreamIntoTheStateIndependentlyOfFavorites() =
        runTest {
            val downloads = FakeDownloadsRepository()
            val viewModel = newViewModel(downloads = downloads)

            downloads.recordDownload(wallpaper(id = "saved-file"))

            assertEquals(
                listOf("saved-file"),
                viewModel.state
                    .first { it.downloads.isNotEmpty() }
                    .downloads
                    .map { it.wallpaper.id },
            )
        }

    @Test
    fun togglingFavoriteOnADownloadNeverTouchesTheDownloadsList() =
        runTest {
            val favorites = FakeFavoritesRepository()
            val downloads = FakeDownloadsRepository()
            val downloaded = wallpaper(id = "file")
            downloads.setDownloads(listOf(Downloaded(downloaded, downloadedAtMillis = 1)))
            val viewModel = newViewModel(favorites = favorites, downloads = downloads)

            viewModel.toggleFavorite(downloaded)

            val state = viewModel.state.first { it.favorites.isNotEmpty() }
            assertEquals(listOf("file"), state.favorites.map { it.wallpaper.id })
            // The download stays: favoriting is not un-downloading.
            assertEquals(listOf("file"), state.downloads.map { it.wallpaper.id })
        }

    @Test
    fun clearHistoryLeavesDownloadsUntouched() =
        runTest {
            val history = FakeHistoryRepository()
            val downloads = FakeDownloadsRepository()
            downloads.setDownloads(listOf(Downloaded(wallpaper(id = "kept-file"), downloadedAtMillis = 1)))
            val viewModel = newViewModel(history = history, downloads = downloads)

            viewModel.clearHistory()

            val state = viewModel.state.first { !it.isLoading && it.history.isEmpty() }
            assertTrue(state.history.isEmpty())
            assertEquals(listOf("kept-file"), state.downloads.map { it.wallpaper.id })
        }

    private fun TestScope.newViewModel(
        favorites: FakeFavoritesRepository = FakeFavoritesRepository(),
        history: FakeHistoryRepository = FakeHistoryRepository(),
        downloads: FakeDownloadsRepository = FakeDownloadsRepository(),
        preferences: UserPreferencesRepository = newPreferences(),
    ): LibraryViewModel = LibraryViewModel(favorites, history, downloads, preferences)

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
