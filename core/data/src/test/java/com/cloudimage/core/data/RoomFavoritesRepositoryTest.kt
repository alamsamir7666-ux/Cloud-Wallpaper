package com.cloudimage.core.data

import com.cloudimage.core.data.repository.RoomFavoritesRepository
import com.cloudimage.core.database.FavoriteDao
import com.cloudimage.core.database.FavoriteEntity
import com.cloudimage.core.model.ContentRating
import com.cloudimage.core.model.Wallpaper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomFavoritesRepositoryTest {
    private val dao = FakeFavoriteDao()
    private val repository = RoomFavoritesRepository(dao)

    private val wallpaper =
        Wallpaper(
            id = "w1",
            providerId = "wallhaven",
            thumbUrl = "https://example.com/w1-thumb.jpg",
            fullUrl = "https://example.com/w1-full.jpg",
            title = "Aurora",
            width = 3840,
            height = 2160,
            contentRating = ContentRating.SFW,
        )

    @Test
    fun toggleFavoriteAddsThenRemoves() {
        runTest {
            repository.toggleFavorite(wallpaper)

            assertEquals(1, repository.observeFavorites().first().size)

            repository.toggleFavorite(wallpaper)

            assertTrue(repository.observeFavorites().first().isEmpty())
        }
    }

    @Test
    fun favoritesMapBackToDomainModel() {
        runTest {
            repository.toggleFavorite(wallpaper)

            val favorite = repository.observeFavorites().first().single()

            assertEquals(wallpaper, favorite.wallpaper)
            assertEquals("Aurora", favorite.wallpaper.title)
            assertTrue(favorite.addedAtMillis > 0)
        }
    }

    @Test
    fun isFavoriteIsScopedToProviderAndWallpaper() {
        runTest {
            repository.toggleFavorite(wallpaper)

            assertTrue(repository.observeIsFavorite("wallhaven", "w1").first())
            assertFalse(repository.observeIsFavorite("wallhaven", "w2").first())
            assertFalse(repository.observeIsFavorite("other", "w1").first())
        }
    }

    @Test
    fun reFavoriteRefreshesSnapshot() {
        runTest {
            repository.toggleFavorite(wallpaper)

            repository.toggleFavorite(wallpaper)
            repository.toggleFavorite(wallpaper.copy(title = "Aurora 2"))

            val favorite = repository.observeFavorites().first().single()
            assertEquals("Aurora 2", favorite.wallpaper.title)
        }
    }
}

/** In-memory FavoriteDao — no Room, no SQL, just state the tests control. */
private class FakeFavoriteDao : FavoriteDao {
    private val entities = MutableStateFlow<List<FavoriteEntity>>(emptyList())

    override suspend fun upsert(favorite: FavoriteEntity) {
        entities.update { current ->
            current.filterNot {
                it.providerId == favorite.providerId && it.wallpaperId == favorite.wallpaperId
            } + favorite
        }
    }

    override suspend fun delete(
        providerId: String,
        wallpaperId: String,
    ) {
        entities.update { current ->
            current.filterNot { it.providerId == providerId && it.wallpaperId == wallpaperId }
        }
    }

    override fun observeAll(): Flow<List<FavoriteEntity>> = entities.asStateFlow()

    override fun observeIsFavorite(
        providerId: String,
        wallpaperId: String,
    ): Flow<Boolean> {
        return entities.map { list ->
            list.any { it.providerId == providerId && it.wallpaperId == wallpaperId }
        }
    }

    override suspend fun isFavorite(
        providerId: String,
        wallpaperId: String,
    ): Boolean {
        return entities.value.any { it.providerId == providerId && it.wallpaperId == wallpaperId }
    }
}
