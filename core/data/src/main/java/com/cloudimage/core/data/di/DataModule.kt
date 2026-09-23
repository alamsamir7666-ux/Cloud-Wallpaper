package com.cloudimage.core.data.di

import com.cloudimage.core.data.platform.AndroidWallpaperApplier
import com.cloudimage.core.data.platform.BitmapDecoder
import com.cloudimage.core.data.platform.BitmapFactoryDecoder
import com.cloudimage.core.data.platform.MediaStoreWallpaperSaver
import com.cloudimage.core.data.platform.SystemWallpaperSetter
import com.cloudimage.core.data.platform.WallpaperManagerSetter
import com.cloudimage.core.data.repository.ExtensionWallpaperSources
import com.cloudimage.core.data.repository.FavoritesRepository
import com.cloudimage.core.data.repository.HistoryRepository
import com.cloudimage.core.data.repository.RoomFavoritesRepository
import com.cloudimage.core.data.repository.RoomHistoryRepository
import com.cloudimage.core.data.repository.WallpaperApplier
import com.cloudimage.core.data.repository.WallpaperSaver
import com.cloudimage.core.data.repository.WallpaperSources
import com.cloudimage.provider.api.ProviderSettings
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

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
    abstract fun bindWallpaperSources(impl: ExtensionWallpaperSources): WallpaperSources

    @Binds
    abstract fun bindProviderSettings(impl: com.cloudimage.core.data.repository.DatastoreProviderSettings): ProviderSettings

    @Binds
    abstract fun bindSystemWallpaperSetter(impl: WallpaperManagerSetter): SystemWallpaperSetter

    @Binds
    abstract fun bindBitmapDecoder(impl: BitmapFactoryDecoder): BitmapDecoder

    @Binds
    abstract fun bindWallpaperApplier(impl: AndroidWallpaperApplier): WallpaperApplier

    @Binds
    abstract fun bindWallpaperSaver(impl: MediaStoreWallpaperSaver): WallpaperSaver
}

/**
 * The application-lifetime coroutine scope for work that outlives a
 * screen: refreshing the wallpaper sources at startup, collecting key
 * stores, reconciling bundled extensions. Cancellation never happens —
 * the process going away is the scope's lifetime.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object DataScopeModule {
    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
