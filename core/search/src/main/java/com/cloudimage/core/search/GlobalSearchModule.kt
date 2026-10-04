package com.cloudimage.core.search

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * Binds the global image search to the Browser repo system's backend.
 *
 * The engine and its [SearchBackendConfig] are constructed here (not via
 * @Inject) because their inputs are deployment wiring: which client they
 * ride, and where the remote-config override is read from. Tests build
 * both directly against MockWebServer instead.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object GlobalSearchModule {
    @Provides
    @Singleton
    fun provideSearchBackendConfig(
        okHttpClient: OkHttpClient,
        json: Json,
    ): SearchBackendConfig = SearchBackendConfig(baseClient = okHttpClient, json = json)

    @Provides
    @Singleton
    fun provideImageSearchEngine(
        config: SearchBackendConfig,
        okHttpClient: OkHttpClient,
        json: Json,
    ): ImageSearchEngine =
        BrowserSearchEngine(
            config = config,
            baseClient = okHttpClient,
            json = json,
        )
}
