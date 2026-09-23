package com.cloudimage.app

import android.content.Context
import com.cloudimage.core.data.repository.WallpaperSources
import com.cloudimage.extensions.core.BundledExtensionsInstaller
import com.cloudimage.extensions.core.BundledPackage
import com.cloudimage.extensions.core.BundledPackageSource
import com.cloudimage.extensions.core.ExtensionRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Wire shape of assets/bundled.json, generated at build time. */
@Serializable
private data class BundledEntry(
    val id: String,
    val fileName: String,
    val sha256: String,
)

/**
 * Startup pass: installs the provider packages this APK bundles in its
 * assets (see the sync task in app/build.gradle.kts), refreshes the
 * engine, and primes the browse sources so the first browse tab visit
 * has content.
 *
 * Runs once per process start on a background dispatcher; the reconcile
 * pass is idempotent, so an app update simply reinstalls whichever
 * bundled package changed.
 */
@Singleton
class AppBootstrapper
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val repository: ExtensionRepository,
        private val sources: WallpaperSources,
        private val appScope: CoroutineScope,
    ) {
        fun start() {
            appScope.launch(Dispatchers.IO) {
                val bundled = readBundled()
                if (bundled.isNotEmpty()) {
                    BundledExtensionsInstaller(
                        bundled = bundled,
                        source = AssetPackageSource(),
                        stagingDir = File(context.cacheDir, "bundled-staging"),
                    ).reconcile(repository)
                }
                sources.refresh()
            }
        }

        private fun readBundled(): List<BundledPackage> =
            runCatching {
                context.assets.open("bundled.json").bufferedReader().use { reader ->
                    json.decodeFromString(ListSerializer(BundledEntry.serializer()), reader.readText())
                        .map { BundledPackage(id = it.id, fileName = it.fileName, sha256 = it.sha256) }
                }
            }.getOrDefault(emptyList())

        /** Reads package bytes straight from the APK's assets directory. */
        private inner class AssetPackageSource : BundledPackageSource {
            override fun open(fileName: String): ByteArray? =
                runCatching { context.assets.open(fileName).use { it.readBytes() } }.getOrNull()
        }

        private companion object {
            val json = Json { ignoreUnknownKeys = true }
        }
    }
