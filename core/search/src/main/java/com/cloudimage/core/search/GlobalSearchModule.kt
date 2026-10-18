package com.cloudimage.core.search

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * Binds the global image search to its merged engine (v1.2.14): the
 * Browser repo system's bridge as the primary lane, and Reddit's public
 * listing API — called directly from the device, riding the installed-app
 * OAuth identity since v1.2.25 — as the secondary.
 *
 * The engine and its [SearchBackendConfig] are constructed here (not via
 * @Inject) because their inputs are deployment wiring: which client they
 * ride, where the remote-config override is read from, and where the
 * per-installation Reddit device id is stored. Tests build both directly
 * against MockWebServer instead.
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
    fun provideRedditDeviceIdStore(
        @ApplicationContext context: Context,
    ): RedditDeviceIdStore = RedditDeviceIdStore.sharedPrefs(context)

    @Provides
    @Singleton
    fun provideImageSearchEngine(
        config: SearchBackendConfig,
        okHttpClient: OkHttpClient,
        json: Json,
        deviceIdStore: RedditDeviceIdStore,
    ): ImageSearchEngine =
        MergedSearchEngine(
            primary = BrowserSearchEngine(config = config, baseClient = okHttpClient, json = json),
            secondary =
                RedditSearchEngine(
                    baseClient = okHttpClient,
                    json = json,
                    oauth =
                        RedditAppAuth(
                            baseClient = okHttpClient,
                            json = json,
                            deviceIdStore = deviceIdStore,
                        ),
                    clientIdProvider = { config.redditClientId() },
                ),
        )
}
