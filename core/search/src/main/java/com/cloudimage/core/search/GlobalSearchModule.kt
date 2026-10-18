package com.cloudimage.core.search

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * Binds the global image search to its merged engine (v1.2.14): the
 * Browser repo system's bridge as the primary lane, and Reddit's public
 * listing API — called directly from the device — as the secondary.
 * v1.2.23 adds the Supabase corpus as a tertiary lane: the shared,
 * seeded search-metadata store, read anonymously over PostgREST with
 * the baked-in public anon key (RLS-locked to SELECT).
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
        MergedSearchEngine(
            primary = BrowserSearchEngine(config = config, baseClient = okHttpClient, json = json),
            secondary = RedditSearchEngine(baseClient = okHttpClient, json = json),
            tertiary = CorpusSearchEngine(config = config, baseClient = okHttpClient, json = json),
        )
}
