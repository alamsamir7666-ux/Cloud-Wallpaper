package com.cloudimage.core.network

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Marks the boolean that toggles HTTP logging. The app module supplies it from
 * `BuildConfig.DEBUG` so release builds never log network traffic.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class NetworkDebugLogging

@Module
@InstallIn(SingletonComponent::class)
internal object NetworkModule {
    /**
     * The disk budget for shared HTTP caching (v1.3.1): 20 MB — hundreds
     * of provider documents, a rounding error against any image cache,
     * and trivial for the cache directory the OS already manages.
     */
    private const val HTTP_CACHE_BYTES = 20L * 1024 * 1024
    @Provides
    @Singleton
    fun provideOkHttpClient(
        @ApplicationContext context: Context,
        @NetworkDebugLogging debugLogging: Boolean,
    ): OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            // v1.3.1: the disk HTTP cache every provider document request
            // now shares. One shared client, one shared cache — OkHttp's
            // contract (a Cache instance must belong to exactly one client)
            // holds because the client is a @Singleton. The freshness that
            // fills it comes from origins that speak for themselves and,
            // for the many that are silent, from
            // [ProviderCachePolicyInterceptor]'s sixty-second default;
            // the stale-on-error seam reads it back when transport dies.
            .cache(Cache(File(context.cacheDir, "provider-http"), HTTP_CACHE_BYTES))
            .addNetworkInterceptor(ProviderCachePolicyInterceptor)
            .apply {
                if (debugLogging) {
                    addInterceptor(
                        HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC },
                    )
                }
            }.build()

    @Provides
    @Singleton
    fun provideJson(): Json =
        Json {
            // Provider APIs evolve; unknown keys must not break decoding.
            ignoreUnknownKeys = true
            // "null" in JSON falls back to the Kotlin default instead of crashing.
            coerceInputValues = true
            // Omit nulls when encoding — keeps payloads small.
            explicitNulls = false
        }

    /**
     * The WebView machinery behind the Cloudflare bypass — the one piece of
     * the HTTP stack that needs an Android [Context]. Kept behind the
     * [CloudflareSolver] interface so the bypasser's state machine stays
     * unit-testable on the JVM.
     *
     * The solver also needs the resumed activity to attach its challenge
     * dialog to a real window (v1.0.16 — detached WebViews never settle);
     * the [ForegroundActivityTracker] is that window's source of truth.
     */
    @Provides
    @Singleton
    fun provideCloudflareSolver(
        @ApplicationContext context: Context,
        tracker: ForegroundActivityTracker,
    ): CloudflareSolver = WebViewCloudflareSolver(context) { tracker.current() }

    @Provides
    @Singleton
    fun provideCloudflareBypasser(solver: CloudflareSolver): CloudflareBypasser = CloudflareBypasser(solver)

    @Provides
    @Singleton
    fun provideCloudimageHttpClient(
        okHttpClient: OkHttpClient,
        json: Json,
        cloudflare: CloudflareBypasser,
    ): CloudimageHttpClient =
        CloudimageHttpClient(
            okHttpClient = okHttpClient,
            json = json,
            cloudflare = cloudflare,
        )

    /** The size sheet's honesty seam, served by the one shared client. */
    @Provides
    @Singleton
    fun provideFileSizeProber(client: CloudimageHttpClient): FileSizeProber = client
}
