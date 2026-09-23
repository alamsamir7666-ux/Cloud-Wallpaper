package com.cloudimage.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Runs the real Room SQL against an in-memory SQLite — catches query typos
 * that compile but fail at runtime.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FavoriteDaoTest {
    private lateinit var database: CloudimageDatabase

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room.inMemoryDatabaseBuilder(context, CloudimageDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun upsertStoresAndObservesFavorites() =
        runTest {
            val dao = database.favoriteDao()

            dao.upsert(favorite(wallpaperId = "1", title = "Aurora"))
            dao.upsert(favorite(wallpaperId = "2", title = "Nebula"))

            val favorites = dao.observeAll().first()
            // Newest first: wallpaper "2" has the larger addedAtMillis.
            assertEquals(listOf("2", "1"), favorites.map { it.wallpaperId })
            assertEquals("Aurora", favorites.first { it.wallpaperId == "1" }.title)
        }

    @Test
    fun upsertReplacesSnapshotForSameWallpaper() =
        runTest {
            val dao = database.favoriteDao()

            dao.upsert(favorite(wallpaperId = "1", title = "Old title"))
            dao.upsert(favorite(wallpaperId = "1", title = "New title"))

            val favorites = dao.observeAll().first()
            assertEquals(1, favorites.size)
            assertEquals("New title", favorites.single().title)
        }

    @Test
    fun isFavoriteReflectsUpsertAndDelete() =
        runTest {
            val dao = database.favoriteDao()

            dao.upsert(favorite(wallpaperId = "1"))
            assertTrue(dao.isFavorite(providerId = "wallhaven", wallpaperId = "1"))
            assertTrue(dao.observeIsFavorite("wallhaven", "1").first())

            dao.delete(providerId = "wallhaven", wallpaperId = "1")
            assertFalse(dao.isFavorite(providerId = "wallhaven", wallpaperId = "1"))
            assertFalse(dao.observeIsFavorite("wallhaven", "1").first())
        }

    @Test
    fun isFavoriteIsScopedToProvider() =
        runTest {
            val dao = database.favoriteDao()

            dao.upsert(favorite(providerId = "wallhaven", wallpaperId = "1"))

            assertTrue(dao.isFavorite("wallhaven", "1"))
            assertFalse(dao.isFavorite("other-provider", "1"))
        }

    private fun favorite(
        providerId: String = "wallhaven",
        wallpaperId: String,
        title: String? = null,
    ) = FavoriteEntity(
        providerId = providerId,
        wallpaperId = wallpaperId,
        thumbUrl = "https://example.com/$wallpaperId-thumb.jpg",
        fullUrl = "https://example.com/$wallpaperId-full.jpg",
        title = title,
        width = 3840,
        height = 2160,
        sourceUrl = null,
        contentRating = "SFW",
        addedAtMillis = 1_000L + (wallpaperId.toLongOrNull() ?: 0L),
    )
}
