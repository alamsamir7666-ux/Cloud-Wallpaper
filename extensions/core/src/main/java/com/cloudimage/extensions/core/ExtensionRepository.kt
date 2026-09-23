package com.cloudimage.extensions.core

import com.cloudimage.provider.api.WallpaperProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The engine facade consumed by UI and, from Part 6 on, the browse
 * pipeline. All disk access happens off the caller's dispatcher.
 */
interface ExtensionRepository {
    /**
     * The installed extensions, or null until the first [refresh] — the
     * UI uses that distinction to show a loading state instead of an
     * empty one on cold start.
     */
    val installed: StateFlow<List<InstalledExtension>?>

    /** Rescans the extensions area; called after every mutation and at startup. */
    suspend fun refresh()

    /** Installs a downloaded package; [expectedSha256] comes from the repository index when known. */
    suspend fun install(
        source: File,
        expectedSha256: String? = null,
    ): InstallResult

    /** Removes an extension; false when [extensionId] is unknown. */
    suspend fun uninstall(extensionId: String): Boolean

    /** Loads — and caches by content checksum — the provider of a READY extension. */
    suspend fun providerFor(extension: InstalledExtension): LoadResult
}

/**
 * Default implementation: file-backed install/uninstall, scanning and
 * provider loading over the seams, with an in-memory provider cache
 * keyed by package checksum (content-addressed, so reinstalls with new
 * content get fresh instances automatically).
 */
@Singleton
class DefaultExtensionRepository
    @Inject
    constructor(
        private val installer: ExtensionInstaller,
        private val scanner: ExtensionScanner,
        private val loader: ExtensionLoader,
        private val ioDispatcher: CoroutineDispatcher,
    ) : ExtensionRepository {
        private val state = MutableStateFlow<List<InstalledExtension>?>(null)
        override val installed: StateFlow<List<InstalledExtension>?> = state.asStateFlow()

        private val providers = ConcurrentHashMap<String, WallpaperProvider>()
        private val loadMutex = Mutex()

        override suspend fun refresh() {
            withContext(ioDispatcher) {
                state.value = scanner.scan()
            }
        }

        override suspend fun install(
            source: File,
            expectedSha256: String?,
        ): InstallResult =
            withContext(ioDispatcher) {
                installer.install(source, expectedSha256)
            }.also {
                if (it is InstallResult.Installed) {
                    refresh()
                }
            }

        override suspend fun uninstall(extensionId: String): Boolean =
            withContext(ioDispatcher) {
                installer.uninstall(extensionId)
            }.also { removed ->
                if (removed) {
                    state.value
                        ?.firstOrNull { it.id == extensionId }
                        ?.let { providers.remove(it.sha256) }
                    refresh()
                }
            }

        override suspend fun providerFor(extension: InstalledExtension): LoadResult {
            providers[extension.sha256]?.let { return LoadResult.Loaded(it) }
            return loadMutex.withLock {
                providers[extension.sha256]?.let { return LoadResult.Loaded(it) }
                when (val result = withContext(ioDispatcher) { loader.load(extension) }) {
                    is LoadResult.Loaded -> {
                        providers[extension.sha256] = result.provider
                        result
                    }
                    is LoadResult.Failed -> result
                }
            }
        }
    }
