package com.cloudimage.extensions.core

import com.cloudimage.provider.api.ProviderApi
import com.cloudimage.provider.api.ProviderHttpClient
import com.cloudimage.provider.api.WallpaperProvider

/**
 * Turns an installed, READY extension into a live [WallpaperProvider]:
 *
 * 1. re-gate the API version (cheap defense against a drifted index),
 * 2. load the entry class through the classloader seam,
 * 3. require a public no-arg constructor and cast to the contract,
 * 4. hand over the shared HTTP client via
 *    [WallpaperProvider.configure].
 *
 * Every failure mode maps to a typed [ExtensionError] — a broken plugin
 * can never take the engine down.
 */
class ExtensionLoader(
    private val dirs: ExtensionDirs,
    private val classLoaderFactory: ExtensionClassLoaderFactory,
    private val httpClient: ProviderHttpClient,
) {
    fun load(extension: InstalledExtension): LoadResult {
        val manifest = extension.manifest
        if (extension.status != ExtensionStatus.READY || manifest == null) {
            return LoadResult.Failed(ExtensionError.NotLoadable(extension.status))
        }
        if (!ProviderApi.isSupported(manifest.apiVersion)) {
            return LoadResult.Failed(
                ExtensionError.UnsupportedApi(declared = manifest.apiVersion, supported = ProviderApi.VERSION),
            )
        }

        return try {
            val loader = classLoaderFactory.createFor(dirs.packageFile(extension.fileName))
            val instance = loader.loadClass(manifest.entryClass).getDeclaredConstructor().newInstance()
            val provider =
                instance as? WallpaperProvider
                    ?: return LoadResult.Failed(ExtensionError.NotAProvider(manifest.entryClass))
            provider.configure(httpClient)
            LoadResult.Loaded(provider)
        } catch (e: ClassNotFoundException) {
            LoadResult.Failed(ExtensionError.EntryClassMissing(manifest.entryClass))
        } catch (e: ClassCastException) {
            LoadResult.Failed(ExtensionError.NotAProvider(manifest.entryClass))
        } catch (e: ReflectiveOperationException) {
            LoadResult.Failed(ExtensionError.InstantiationFailed(e))
        } catch (e: LinkageError) {
            LoadResult.Failed(ExtensionError.InstantiationFailed(e))
        } catch (e: Exception) {
            LoadResult.Failed(ExtensionError.ProviderSetupFailed(e))
        }
    }
}
