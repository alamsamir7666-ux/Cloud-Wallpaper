package com.cloudimage.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The single app database.
 *
 * `exportSchema = false` keeps Part 2 lean; Part 8 turns schema export +
 * migration tests on before the v1.0.0 release.
 */
@Database(
    entities = [FavoriteEntity::class, HistoryEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class CloudimageDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao

    abstract fun historyDao(): HistoryDao
}
