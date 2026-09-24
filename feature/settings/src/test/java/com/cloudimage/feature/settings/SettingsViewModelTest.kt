package com.cloudimage.feature.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.cloudimage.core.datastore.UserPreferencesRepository
import com.cloudimage.core.model.Favorite
import com.cloudimage.core.model.HistoryAction
import com.cloudimage.core.model.HistoryEntry
import com.cloudimage.core.model.Wallpaper
import com.cloudimage.core.network.NetworkError
import com.cloudimage.core.network.NetworkResult
import com.cloudimage.core.testing.FakeAppUpdateRepository
import com.cloudimage.core.testing.FakeFavoritesRepository
import com.cloudimage.core.testing.FakeHistoryRepository
import com.cloudimage.core.testing.FakeUpdateInstaller
import com.cloudimage.core.testing.MainDispatcherRule
import com.cloudimage.core.testing.appUpdate
import com.cloudimage.core.testing.installFailure
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

    @Test
    fun updateCheckMarksNewerReleasesAvailable() =
        runTest {
            val updates = FakeAppUpdateRepository()
            updates.enqueue(NetworkResult.Success(appUpdate(tagName = "v9.9.9")))
            val viewModel = newViewModel(updates = updates)

            viewModel.checkForUpdate()

            val update = viewModel.state.first { it.update is UpdateState.Available }.update
            assertEquals("v9.9.9", (update as UpdateState.Available).update.tagName)
        }

    @Test
    fun updateCheckMarksSameVersionUpToDate() =
        runTest {
            val updates = FakeAppUpdateRepository()
            updates.enqueue(NetworkResult.Success(appUpdate(tagName = "v1.2.3")))
            val viewModel = newViewModel(updates = updates)

            viewModel.checkForUpdate()

            assertTrue(viewModel.state.first { it.update is UpdateState.UpToDate }.update is UpdateState.UpToDate)
        }

    @Test
    fun updateCheckMapsNetworkFailures() =
        runTest {
            val updates = FakeAppUpdateRepository()
            updates.enqueue(NetworkResult.Failure(NetworkError.Io(java.io.IOException())))
            val viewModel = newViewModel(updates = updates)

            viewModel.checkForUpdate()

            assertEquals(
                UpdateError.OFFLINE,
                (viewModel.state.first { it.update is UpdateState.Failed }.update as UpdateState.Failed).error,
            )
        }

    @Test
    fun downloadAndInstallHandsTheUpdateToTheInstaller() =
        runTest {
            val updates = FakeAppUpdateRepository()
            val installer = FakeUpdateInstaller()
            val newer = appUpdate(tagName = "v9.9.9")
            updates.enqueue(NetworkResult.Success(newer))
            val viewModel = newViewModel(updates = updates, installer = installer)
            viewModel.checkForUpdate()
            viewModel.state.first { it.update is UpdateState.Available }

            viewModel.downloadAndInstall()

            assertEquals(listOf(newer), installer.installed)
            // Started -> the card becomes actionable again for a retry.
            assertTrue(viewModel.state.first { it.update is UpdateState.Available }.update is UpdateState.Available)
        }

    @Test
    fun downloadFailureSurfacesAnInstallError() =
        runTest {
            val updates = FakeAppUpdateRepository()
            val installer = FakeUpdateInstaller()
            installer.enqueue(installFailure())
            updates.enqueue(NetworkResult.Success(appUpdate(tagName = "v9.9.9")))
            val viewModel = newViewModel(updates = updates, installer = installer)
            viewModel.checkForUpdate()
            viewModel.state.first { it.update is UpdateState.Available }

            viewModel.downloadAndInstall()

            assertEquals(
                UpdateError.INSTALL,
                (viewModel.state.first { it.update is UpdateState.Failed }.update as UpdateState.Failed).error,
            )
        }

    private fun TestScope.newViewModel(
        favorites: FakeFavoritesRepository = FakeFavoritesRepository(),
        history: FakeHistoryRepository = FakeHistoryRepository(),
        preferences: UserPreferencesRepository = newPreferences(),
        updates: FakeAppUpdateRepository = FakeAppUpdateRepository(),
        installer: FakeUpdateInstaller = FakeUpdateInstaller(),
    ): SettingsViewModel =
        SettingsViewModel(
            userPreferencesRepository = preferences,
            appUpdateRepository = updates,
            updateInstaller = installer,
            favoritesRepository = favorites,
            historyRepository = history,
            versionName = VersionName("1.2.3"),
        )

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
