package com.cloudimage.core.network

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
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
    @Provides
    @Singleton
    fun provideOkHttpClient(
        @NetworkDebugLogging debugLogging: Boolean,
    ): OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
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
     */
    @Provides
    @Singleton
    fun provideCloudflareSolver(
        @ApplicationContext context: Context,
    ): CloudflareSolver = WebViewCloudflareSolver(context)

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
}
