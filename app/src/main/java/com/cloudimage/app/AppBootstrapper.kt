package com.cloudimage.app

import android.content.Context
import com.cloudimage.core.data.repository.WallpaperSources
import com.cloudimage.core.data.rotation.RotationScheduler
import com.cloudimage.core.datastore.UserPreferencesRepository
import com.cloudimage.extensions.core.BundledExtensionsInstaller
import com.cloudimage.extensions.core.BundledPackage
import com.cloudimage.extensions.core.BundledPackageSource
import com.cloudimage.extensions.core.ExtensionRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
 * engine, primes the browse sources so the first browse tab visit
 * has content, and keeps the periodic wallpaper rotation in lockstep
 * with the stored settings.
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
        private val rotationScheduler: RotationScheduler,
        private val userPreferencesRepository: UserPreferencesRepository,
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

            // Rotation: every settings change re-syncs the periodic work;
            // unchanged settings leave the running cycle untouched.
            appScope.launch {
                userPreferencesRepository.preferences
                    .map { it.rotation }
                    .distinctUntilChanged()
                    .collect { settings -> rotationScheduler.sync(settings) }
            }
        }

        private fun readBundled(): List<BundledPackage> =
            runCatching {
                context.assets.open("bundled.json").bufferedReader().use { reader ->
                    json
                        .decodeFromString(ListSerializer(BundledEntry.serializer()), reader.readText())
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
