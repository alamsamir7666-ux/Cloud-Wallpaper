package com.cloudimage.core.data.repository

import com.cloudimage.core.data.mapper.toFavorite
import com.cloudimage.core.data.mapper.toFavoriteEntity
import com.cloudimage.core.data.mapper.toHistoryEntity
import com.cloudimage.core.data.mapper.toHistoryEntry
import com.cloudimage.core.database.FavoriteDao
import com.cloudimage.core.database.HistoryDao
import com.cloudimage.core.model.Favorite
import com.cloudimage.core.model.HistoryAction
import com.cloudimage.core.model.HistoryEntry
import com.cloudimage.core.model.Wallpaper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class RoomFavoritesRepository
    @Inject
    constructor(
        private val favoriteDao: FavoriteDao,
    ) : FavoritesRepository {
        override fun observeFavorites(): Flow<List<Favorite>> =
            favoriteDao.observeAll().map { entities ->
                entities.map { it.toFavorite() }
            }

        override fun observeIsFavorite(
            providerId: String,
            wallpaperId: String,
        ): Flow<Boolean> = favoriteDao.observeIsFavorite(providerId, wallpaperId)

        override suspend fun toggleFavorite(wallpaper: Wallpaper) {
            if (favoriteDao.isFavorite(wallpaper.providerId, wallpaper.id)) {
                favoriteDao.delete(wallpaper.providerId, wallpaper.id)
            } else {
                favoriteDao.upsert(wallpaper.toFavoriteEntity())
            }
        }
    }

@Singleton
internal class RoomHistoryRepository
    @Inject
    constructor(
        private val historyDao: HistoryDao,
    ) : HistoryRepository {
        override fun observeRecent(limit: Int): Flow<List<HistoryEntry>> =
            historyDao.observeRecent(limit).map { entities -> entities.map { it.toHistoryEntry() } }

        override suspend fun record(
            wallpaper: Wallpaper,
            action: HistoryAction,
        ) {
            historyDao.insert(wallpaper.toHistoryEntity(action, atMillis = System.currentTimeMillis()))
        }

        override suspend fun clear() = historyDao.clearAll()
    }
