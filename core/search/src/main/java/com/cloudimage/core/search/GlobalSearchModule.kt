package com.cloudimage.core.search

import com.cloudimage.core.network.CloudflareBypasser
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * Binds the global image search to its merged engine: the Browser repo
 * system's bridge as the primary lane, and Reddit's public JSON listings
 * — called directly from the device, riding NO credential of any kind
 * (the keyless lane, v1.3.3: no developer-registered client id, no OAuth
 * token, no API key) — as the secondary.
 *
 * v1.3.4: the Reddit lane grew its second rung — the browser-engine ask.
 * Reddit's "blocked by network security" wall refuses every HTTP-client
 * shape from whole address ranges (verified: even clean corporate egress
 * gets the wall on every `.json` door, and `old.reddit.com` sits behind
 * a full login wall), while the same origins serve the real WebView —
 * the phone's actual Chromium — the pages a signed-out user browses. The
 * lane therefore wires [CloudflareBypasser.evaluateOnPageQuiet] behind
 * its [EngineScriptLane] seam: when the plain OkHttp ask is refused, the
 * same `.json` path is fetched same-origin from inside a settled Reddit
 * page loaded invisibly in the engine — the POST lane's proven in-page
 * discipline, and still no credential anywhere.
 *
 * The engines and their [SearchBackendConfig] are constructed here (not
 * via @Inject) because their inputs are deployment wiring: which client
 * they ride and where the remote-config override is read from. Tests
 * build both directly against MockWebServer instead.
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
        cloudflare: CloudflareBypasser,
    ): ImageSearchEngine =
        MergedSearchEngine(
            primary = BrowserSearchEngine(config = config, baseClient = okHttpClient, json = json),
            secondary =
                RedditSearchEngine(
                    baseClient = okHttpClient,
                    json = json,
                    engine = EngineScriptLane { pageUrl, js -> cloudflare.evaluateOnPageQuiet(pageUrl, js) },
                ),
        )
}
