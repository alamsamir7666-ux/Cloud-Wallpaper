package com.cloudimage.core.data.di

import com.cloudimage.core.data.repository.FavoritesRepository
import com.cloudimage.core.data.repository.HistoryRepository
import com.cloudimage.core.data.repository.RoomFavoritesRepository
import com.cloudimage.core.data.repository.RoomHistoryRepository
import com.cloudimage.core.data.repository.WallhavenRepository
import com.cloudimage.core.data.repository.WallhavenRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Binds repository interfaces to their Room-backed implementations.
 * Features inject the interfaces; only tests ever see a fake.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class DataModule {
    @Binds
    abstract fun bindFavoritesRepository(impl: RoomFavoritesRepository): FavoritesRepository

    @Binds
    abstract fun bindHistoryRepository(impl: RoomHistoryRepository): HistoryRepository

    @Binds
    abstract fun bindWallhavenRepository(impl: WallhavenRepositoryImpl): WallhavenRepository
}
