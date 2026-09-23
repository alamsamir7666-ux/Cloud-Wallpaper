package com.cloudimage.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    /** Inserts or replaces — re-favoriting refreshes the stored snapshot. */
    @Upsert
    suspend fun upsert(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE providerId = :providerId AND wallpaperId = :wallpaperId")
    suspend fun delete(
        providerId: String,
        wallpaperId: String,
    )

    @Query("SELECT * FROM favorites ORDER BY addedAtMillis DESC")
    fun observeAll(): Flow<List<FavoriteEntity>>

    @Query(
        "SELECT EXISTS(SELECT 1 FROM favorites WHERE providerId = :providerId AND wallpaperId = :wallpaperId)",
    )
    fun observeIsFavorite(
        providerId: String,
        wallpaperId: String,
    ): Flow<Boolean>

    @Query(
        "SELECT EXISTS(SELECT 1 FROM favorites WHERE providerId = :providerId AND wallpaperId = :wallpaperId)",
    )
    suspend fun isFavorite(
        providerId: String,
        wallpaperId: String,
    ): Boolean
}

@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(entry: HistoryEntity)

    @Query("SELECT * FROM history ORDER BY atMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<HistoryEntity>>

    @Query("DELETE FROM history")
    suspend fun clearAll()
}
