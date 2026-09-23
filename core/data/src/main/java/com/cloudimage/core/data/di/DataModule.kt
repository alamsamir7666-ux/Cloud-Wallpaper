package com.cloudimage.core.data.di

import com.cloudimage.core.data.platform.AndroidWallpaperApplier
import com.cloudimage.core.data.platform.BitmapDecoder
import com.cloudimage.core.data.platform.BitmapFactoryDecoder
import com.cloudimage.core.data.platform.MediaStoreWallpaperSaver
import com.cloudimage.core.data.platform.SystemWallpaperSetter
import com.cloudimage.core.data.platform.WallpaperManagerSetter
import com.cloudimage.core.data.repository.FavoritesRepository
import com.cloudimage.core.data.repository.HistoryRepository
import com.cloudimage.core.data.repository.RoomFavoritesRepository
import com.cloudimage.core.data.repository.RoomHistoryRepository
import com.cloudimage.core.data.repository.WallhavenRepository
import com.cloudimage.core.data.repository.WallhavenRepositoryImpl
import com.cloudimage.core.data.repository.WallpaperApplier
import com.cloudimage.core.data.repository.WallpaperSaver
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

    @Binds
    abstract fun bindSystemWallpaperSetter(impl: WallpaperManagerSetter): SystemWallpaperSetter

    @Binds
    abstract fun bindBitmapDecoder(impl: BitmapFactoryDecoder): BitmapDecoder

    @Binds
    abstract fun bindWallpaperApplier(impl: AndroidWallpaperApplier): WallpaperApplier

    @Binds
    abstract fun bindWallpaperSaver(impl: MediaStoreWallpaperSaver): WallpaperSaver
}
