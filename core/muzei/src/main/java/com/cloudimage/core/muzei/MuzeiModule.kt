package com.cloudimage.core.muzei

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Binds the Muzei source interfaces to their production implementations.
 * The worker and provider live in this module; the app only pulls the
 * manifest-declared pieces in.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class MuzeiModule {
    @Binds
    abstract fun bindMuzeiArtworkSelector(impl: FavoriteMuzeiArtworkSelector): MuzeiArtworkSelector
}
