package com.cloudimage.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HistoryDaoTest {
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
    fun observeRecentOrdersNewestFirstAndRespectsLimit() =
        runTest {
            val dao = database.historyDao()

            dao.insert(entry(action = "VIEWED", atMillis = 100L))
            dao.insert(entry(action = "APPLIED", atMillis = 300L))
            dao.insert(entry(action = "DOWNLOADED", atMillis = 200L))

            val recent = dao.observeRecent(limit = 2).first()

            assertEquals(listOf(300L, 200L), recent.map { it.atMillis })
            assertEquals("APPLIED", recent.first().action)
        }

    @Test
    fun clearAllRemovesEveryEntry() =
        runTest {
            val dao = database.historyDao()

            dao.insert(entry(action = "VIEWED", atMillis = 100L))
            dao.insert(entry(action = "VIEWED", atMillis = 200L))

            dao.clearAll()

            assertTrue(dao.observeRecent(limit = 50).first().isEmpty())
        }

    private fun entry(
        action: String,
        atMillis: Long,
    ) = HistoryEntity(
        providerId = "wallhaven",
        wallpaperId = "w-$atMillis",
        thumbUrl = "https://example.com/$atMillis-thumb.jpg",
        fullUrl = "https://example.com/$atMillis-full.jpg",
        title = null,
        width = null,
        height = null,
        sourceUrl = null,
        contentRating = "SFW",
        action = action,
        atMillis = atMillis,
    )
}
