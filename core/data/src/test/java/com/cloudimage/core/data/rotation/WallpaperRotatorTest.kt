package com.cloudimage.core.data.rotation

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.work.NetworkType
import com.cloudimage.core.data.repository.ApplyError
import com.cloudimage.core.data.repository.ApplyResult
import com.cloudimage.core.data.repository.ApplyTarget
import com.cloudimage.core.data.repository.FavoritesRepository
import com.cloudimage.core.data.repository.HistoryRepository
import com.cloudimage.core.data.repository.WallpaperApplier
import com.cloudimage.core.datastore.UserPreferencesRepository
import com.cloudimage.core.model.Favorite
import com.cloudimage.core.model.HistoryAction
import com.cloudimage.core.model.HistoryEntry
import com.cloudimage.core.model.RotationTarget
import com.cloudimage.core.model.Wallpaper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Local (non-:core:testing) fakes: :core:testing depends on this module, so
 * importing it here would be circular.
 */
private class LocalFavoritesRepository : FavoritesRepository {
    private val favorites = MutableStateFlow<List<Favorite>>(emptyList())

    override fun observeFavorites(): Flow<List<Favorite>> = favorites

    override fun observeIsFavorite(
        providerId: String,
        wallpaperId: String,
    ): Flow<Boolean> =
        favorites.map { list ->
            list.any { it.wallpaper.providerId == providerId && it.wallpaper.id == wallpaperId }
        }

    override suspend fun toggleFavorite(wallpaper: Wallpaper) = Unit

    fun set(values: List<Favorite>) {
        favorites.value = values
    }
}

private class LocalHistoryRepository : HistoryRepository {
    val recorded = mutableListOf<Pair<Wallpaper, HistoryAction>>()
    private val entries = MutableStateFlow<List<HistoryEntry>>(emptyList())

    override fun observeRecent(limit: Int): Flow<List<HistoryEntry>> = entries

    override suspend fun record(
        wallpaper: Wallpaper,
        action: HistoryAction,
    ) {
        recorded += wallpaper to action
    }

    override suspend fun clear() = Unit
}

private class LocalApplier : WallpaperApplier {
    val calls = mutableListOf<Pair<Wallpaper, ApplyTarget>>()
    var result: ApplyResult = ApplyResult.Success

    override suspend fun apply(
        wallpaper: Wallpaper,
        target: ApplyTarget,
    ): ApplyResult {
        calls += wallpaper to target
        return result
    }
}

class WallpaperRotatorTest {
    @get:Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder.builder().assureDeletion().build()

    private fun TestScope.newRotator(
        favorites: LocalFavoritesRepository = LocalFavoritesRepository(),
        history: LocalHistoryRepository = LocalHistoryRepository(),
        applier: LocalApplier = LocalApplier(),
    ): WallpaperRotator {
        val preferences = newPreferences()
        return FavoriteWallpaperRotator(
            favoritesRepository = favorites,
            historyRepository = history,
            applier = applier,
            userPreferencesRepository = preferences,
        )
    }

    @Test
    fun rotatesThroughFavoritesInSaveOrder() =
        runTest {
            val favorites = LocalFavoritesRepository()
            favorites.set(
                listOf(
                    favorite(id = "old", addedAtMillis = 1),
                    favorite(id = "middle", addedAtMillis = 2),
                    favorite(id = "new", addedAtMillis = 3),
                ),
            )
            val applier = LocalApplier()
            val rotator = newRotator(favorites = favorites, applier = applier)

            rotator.rotateOnce(RotationTarget.HOME)
            rotator.rotateOnce(RotationTarget.HOME)
            rotator.rotateOnce(RotationTarget.HOME)

            assertEquals(listOf("old", "middle", "new"), applier.calls.map { it.first.id })
        }

    @Test
    fun wrapsAroundToTheOldestFavorite() =
        runTest {
            val favorites = LocalFavoritesRepository()
            favorites.set(
                listOf(
                    favorite(id = "a", addedAtMillis = 1),
                    favorite(id = "b", addedAtMillis = 2),
                ),
            )
            val applier = LocalApplier()
            val rotator = newRotator(favorites = favorites, applier = applier)
            rotator.rotateOnce(RotationTarget.HOME)
            rotator.rotateOnce(RotationTarget.HOME)

            rotator.rotateOnce(RotationTarget.HOME)

            assertEquals(
                "a",
                applier.calls
                    .last()
                    .first.id,
            )
        }

    @Test
    fun aCursorPointingAtARemovedFavoriteRestartsFromTheOldest() =
        runTest {
            // "gone" was unfavorited after its turn; the cursor still
            // points at it, but it is no longer in the rotation set.
            val favorites = LocalFavoritesRepository()
            favorites.set(
                listOf(
                    favorite(id = "a", addedAtMillis = 1),
                    favorite(id = "c", addedAtMillis = 3),
                ),
            )
            val preferences = newPreferences()
            val rotator =
                FavoriteWallpaperRotator(
                    favoritesRepository = favorites,
                    historyRepository = LocalHistoryRepository(),
                    applier = LocalApplier(),
                    userPreferencesRepository = preferences,
                )
            preferences.setLastRotationKey("wallhaven/gone")

            val result = rotator.rotateOnce(RotationTarget.HOME)

            assertTrue(result is RotationResult.Success)
            assertEquals("a", (result as RotationResult.Success).wallpaper.id)
        }

    @Test
    fun noSavedWallpapersIsReportedNotCrashed() =
        runTest {
            val applier = LocalApplier()
            val rotator = newRotator(favorites = LocalFavoritesRepository(), applier = applier)

            val result = rotator.rotateOnce(RotationTarget.HOME)

            assertEquals(RotationResult.NoWallpapers, result)
            assertTrue(applier.calls.isEmpty())
        }

    @Test
    fun applyFailuresAreReportedAndTheCursorStillAdvances() =
        runTest {
            val favorites = LocalFavoritesRepository()
            favorites.set(
                listOf(
                    favorite(id = "a", addedAtMillis = 1),
                    favorite(id = "b", addedAtMillis = 2),
                ),
            )
            val applier = LocalApplier()
            applier.result = ApplyResult.Failure(ApplyError.OFFLINE)
            val preferences = newPreferences()
            val rotator =
                FavoriteWallpaperRotator(
                    favoritesRepository = favorites,
                    historyRepository = LocalHistoryRepository(),
                    applier = applier,
                    userPreferencesRepository = preferences,
                )

            val first = rotator.rotateOnce(RotationTarget.HOME)
            val second = rotator.rotateOnce(RotationTarget.HOME)

            assertEquals(ApplyError.OFFLINE, (first as RotationResult.ApplyFailed).error)
            assertEquals(ApplyError.OFFLINE, (second as RotationResult.ApplyFailed).error)
            // "a" was attempted and failed; the cursor already moved past it,
            // so the next run picks "b" instead of hammering the same URL.
            assertEquals(listOf("a", "b"), applier.calls.map { it.first.id })
        }

    @Test
    fun successfulRotationRecordsAppliedHistory() =
        runTest {
            val favorites = LocalFavoritesRepository()
            favorites.set(listOf(favorite(id = "a", addedAtMillis = 1)))
            val history = LocalHistoryRepository()
            val rotator = newRotator(favorites = favorites, history = history)

            rotator.rotateOnce(RotationTarget.HOME)

            assertEquals(listOf("a" to HistoryAction.APPLIED), history.recorded.map { it.first.id to it.second })
        }

    @Test
    fun rotationTargetsMapOntoApplyTargets() =
        runTest {
            val favorites = LocalFavoritesRepository()
            favorites.set(
                listOf(
                    favorite(id = "a", addedAtMillis = 1),
                    favorite(id = "b", addedAtMillis = 2),
                    favorite(id = "c", addedAtMillis = 3),
                ),
            )
            val applier = LocalApplier()
            val rotator = newRotator(favorites = favorites, applier = applier)

            rotator.rotateOnce(RotationTarget.HOME)
            rotator.rotateOnce(RotationTarget.LOCK)
            rotator.rotateOnce(RotationTarget.BOTH)

            assertEquals(listOf(ApplyTarget.HOME, ApplyTarget.LOCK, ApplyTarget.BOTH), applier.calls.map { it.second })
        }

    @Test
    fun emptyPickReturnsNull() {
        assertNull(pickNextRotation(emptyList(), lastKey = null))
    }

    @Test
    fun singleFavoriteRepeatsItself() {
        val only = favorite(id = "only", addedAtMillis = 1)

        val picked = pickNextRotation(listOf(only), lastKey = "wallhaven/only")

        assertEquals("only", picked?.id)
    }

    @Test
    fun wifiOnlyRotationRequiresUnmeteredNetwork() {
        val constraints = rotationConstraints(wifiOnly = true)

        assertTrue(constraints.requiresBatteryNotLow())
        assertEquals(NetworkType.UNMETERED, constraints.requiredNetworkType)
    }

    @Test
    fun anyNetworkRotationStaysMeteredTolerant() {
        val constraints = rotationConstraints(wifiOnly = false)

        assertTrue(constraints.requiresBatteryNotLow())
        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
    }

    private fun TestScope.newPreferences(): UserPreferencesRepository =
        UserPreferencesRepository(
            PreferenceDataStoreFactory.create(scope = backgroundScope) {
                tmpFolder.newFile("rotation_${System.nanoTime()}.preferences_pb")
            },
        )

    private fun favorite(
        id: String,
        addedAtMillis: Long,
    ): Favorite =
        Favorite(
            wallpaper =
                Wallpaper(
                    id = id,
                    providerId = "wallhaven",
                    thumbUrl = "https://example.com/$id-thumb.jpg",
                    fullUrl = "https://example.com/$id.jpg",
                ),
            addedAtMillis = addedAtMillis,
        )
}
