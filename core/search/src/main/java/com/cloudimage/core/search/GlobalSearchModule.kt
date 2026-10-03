package com.cloudimage.core.search

import com.cloudimage.core.network.CloudimageHttpClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the global image search engine. The engine is constructed here (not
 * via @Inject) because its base URL is a deployment constant tests need to
 * override — MockWebServer points it at localhost.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object GlobalSearchModule {
    @Provides
    @Singleton
    fun provideImageSearchEngine(client: CloudimageHttpClient): ImageSearchEngine =
        DuckDuckGoImageSearchEngine(client = client, baseUrl = "https://duckduckgo.com")
}
