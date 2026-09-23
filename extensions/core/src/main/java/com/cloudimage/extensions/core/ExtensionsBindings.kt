package com.cloudimage.extensions.core

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Interface-to-implementation bindings of the extension engine layer. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class ExtensionsBindings {
    @Binds
    abstract fun bindRepoManager(impl: DefaultRepoManager): RepoManager
}
