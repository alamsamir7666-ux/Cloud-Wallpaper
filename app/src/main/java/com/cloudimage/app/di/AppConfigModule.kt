package com.cloudimage.app.di

import com.cloudimage.app.BuildConfig
import com.cloudimage.core.network.NetworkDebugLogging
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * App-level configuration for the shared network stack.
 *
 * HTTP logging is wired to BuildConfig.DEBUG so debug builds log request lines
 * for easier development while release builds stay silent — no tokens or
 * URLs in logcat for end users.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object AppConfigModule {
    @Provides
    @NetworkDebugLogging
    fun provideNetworkDebugLogging(): Boolean = BuildConfig.DEBUG
}
