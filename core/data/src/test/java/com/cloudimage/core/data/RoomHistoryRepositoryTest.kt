package com.cloudimage.core.data

import com.cloudimage.core.data.repository.RoomHistoryRepository
import com.cloudimage.core.database.HistoryDao
import com.cloudimage.core.database.HistoryEntity
import com.cloudimage.core.model.HistoryAction
import com.cloudimage.core.model.Wallpaper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomHistoryRepositoryTest {
    private val dao = FakeHistoryDao()
    private val repository = RoomHistoryRepository(dao)

    private val wallpaper =
        Wallpaper(
            id = "w1",
            providerId = "wallhaven",
            thumbUrl = "https://example.com/w1-thumb.jpg",
            fullUrl = "https://example.com/w1-full.jpg",
        )

    @Test
    fun recordStoresEntryWithActionAndTimestamp() {
        runTest {
            repository.record(wallpaper, HistoryAction.APPLIED)

            val entry = repository.observeRecent().first().single()

            assertEquals(wallpaper, entry.wallpaper)
            assertEquals(HistoryAction.APPLIED, entry.action)
            assertTrue(entry.atMillis > 0)
        }
    }

    @Test
    fun historyIsOrderedNewestFirst() {
        runTest {
            dao.atMillisToUse = 100L
            repository.record(wallpaper, HistoryAction.VIEWED)
            dao.atMillisToUse = 300L
            repository.record(wallpaper, HistoryAction.APPLIED)
            dao.atMillisToUse = 200L
            repository.record(wallpaper, HistoryAction.DOWNLOADED)

            val actions = repository.observeRecent().first().map { it.action }

            assertEquals(
                listOf(HistoryAction.APPLIED, HistoryAction.DOWNLOADED, HistoryAction.VIEWED),
                actions,
            )
        }
    }

    @Test
    fun clearRemovesEverything() {
        runTest {
            repository.record(wallpaper, HistoryAction.VIEWED)

            repository.clear()

            assertTrue(repository.observeRecent().first().isEmpty())
        }
    }
}

/** In-memory HistoryDao with an injectable clock for deterministic tests. */
private class FakeHistoryDao : HistoryDao {
    var atMillisToUse = System.currentTimeMillis()
    private val entities = MutableStateFlow<List<HistoryEntity>>(emptyList())

    override suspend fun insert(entry: HistoryEntity) {
        entities.update { current ->
            current +
                entry.copy(
                    id = (current.maxOfOrNull { it.id } ?: 0L) + 1L,
                    atMillis = atMillisToUse,
                )
        }
    }

    override fun observeRecent(limit: Int): Flow<List<HistoryEntity>> =
        entities.map { list -> list.sortedByDescending { it.atMillis }.take(limit) }

    override suspend fun clearAll() {
        entities.value = emptyList()
    }
}
