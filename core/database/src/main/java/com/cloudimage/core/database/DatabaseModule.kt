package com.cloudimage.core.database

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): CloudimageDatabase =
        Room.databaseBuilder(
            context = context,
            klass = CloudimageDatabase::class.java,
            name = "cloudimage.db",
        )
            // Acceptable while pre-1.0: schema churn wipes local data.
            // Part 8 adds real migrations before release.
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideFavoriteDao(database: CloudimageDatabase): FavoriteDao = database.favoriteDao()

    @Provides
    fun provideHistoryDao(database: CloudimageDatabase): HistoryDao = database.historyDao()
}
