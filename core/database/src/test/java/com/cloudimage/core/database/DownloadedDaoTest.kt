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
class DownloadedDaoTest {
    private lateinit var database: CloudimageDatabase

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, CloudimageDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun upsertStoresAndObservesDownloadsNewestFirst() =
        runTest {
            val dao = database.downloadedDao()

            dao.upsert(downloaded(wallpaperId = "1"))
            dao.upsert(downloaded(wallpaperId = "2", downloadedAtMillis = 2_000))

            val all = dao.observeAll().first()
            assertEquals(listOf("2", "1"), all.map { it.wallpaperId })
        }

    @Test
    fun upsertReplacesSnapshotForSameWallpaper() =
        runTest {
            val dao = database.downloadedDao()

            dao.upsert(downloaded(wallpaperId = "1", title = "Old title"))
            dao.upsert(downloaded(wallpaperId = "1", title = "New title", downloadedAtMillis = 5_000))

            val all = dao.observeAll().first()
            assertEquals(1, all.size)
            assertEquals("New title", all.single().title)
            assertEquals(5_000L, all.single().downloadedAtMillis)
        }

    @Test
    fun observeIsDownloadedIsScopedToTheWallpaperIdentity() =
        runTest {
            val dao = database.downloadedDao()

            dao.upsert(downloaded(providerId = "wallhaven", wallpaperId = "1"))

            assertTrue(dao.observeIsDownloaded("wallhaven", "1").first())
            assertFalse(dao.observeIsDownloaded("other-provider", "1").first())
            assertFalse(dao.observeIsDownloaded("wallhaven", "2").first())
        }

    private fun downloaded(
        providerId: String = "wallhaven",
        wallpaperId: String,
        title: String? = null,
        downloadedAtMillis: Long = 1_000L,
    ) = DownloadedEntity(
        providerId = providerId,
        wallpaperId = wallpaperId,
        thumbUrl = "https://example.com/$wallpaperId-thumb.jpg",
        fullUrl = "https://example.com/$wallpaperId-full.jpg",
        title = title,
        width = 3840,
        height = 2160,
        sourceUrl = null,
        contentRating = "SFW",
        downloadedAtMillis = downloadedAtMillis,
    )
}
