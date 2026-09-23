package com.cloudimage.extensions.core

import android.content.Context
import com.cloudimage.provider.api.ProviderSettings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object ExtensionsModule {
    @Provides
    @Singleton
    fun provideExtensionDirs(
        @ApplicationContext context: Context,
    ): ExtensionDirs = ExtensionDirs(File(context.filesDir, "extensions"))

    @Provides
    @Singleton
    fun provideExtensionIndex(dirs: ExtensionDirs): ExtensionIndex = ExtensionIndex(dirs.indexFile)

    @Provides
    @Singleton
    fun provideInstaller(
        dirs: ExtensionDirs,
        index: ExtensionIndex,
    ): ExtensionInstaller = ExtensionInstaller(dirs, index)

    @Provides
    @Singleton
    fun provideScanner(
        dirs: ExtensionDirs,
        index: ExtensionIndex,
    ): ExtensionScanner = ExtensionScanner(dirs, index)

    @Provides
    @Singleton
    fun provideClassLoaderFactory(): ExtensionClassLoaderFactory = DexExtensionClassLoaderFactory()

    @Provides
    @Singleton
    fun provideLoader(
        dirs: ExtensionDirs,
        classLoaderFactory: ExtensionClassLoaderFactory,
        httpClient: CloudimageProviderHttpClient,
        settings: ProviderSettings,
    ): ExtensionLoader = ExtensionLoader(dirs, classLoaderFactory, httpClient, settings)

    @Provides
    @Singleton
    fun provideExtensionRepository(
        installer: ExtensionInstaller,
        scanner: ExtensionScanner,
        loader: ExtensionLoader,
    ): ExtensionRepository =
        DefaultExtensionRepository(
            installer = installer,
            scanner = scanner,
            loader = loader,
            ioDispatcher = Dispatchers.IO,
        )

    @Provides
    @Singleton
    fun provideRepoStore(
        @ApplicationContext context: Context,
    ): RepoStore = RepoStore(File(context.filesDir, "repos/repos.json"))

    @Provides
    @Singleton
    fun provideExtensionDownloadsDir(
        @ApplicationContext context: Context,
    ): File = File(context.cacheDir, "extension-downloads")

    @Provides
    fun provideNowMillis(): () -> Long = System::currentTimeMillis
}
