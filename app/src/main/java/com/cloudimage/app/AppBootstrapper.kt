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
    /** The package's versionCode — the reconcile pass's ordering signal (v1.2.21). */
    val versionCode: Int,
)

/**
 * Startup pass: installs the provider packages this APK bundles in its
 * assets (see the sync task in app/build.gradle.kts), retires the ids
 * this build no longer sanctions, refreshes the engine, primes the
 * browse sources so the first browse tab visit has content, and keeps
 * the periodic wallpaper rotation in lockstep with the stored settings.
 *
 * Runs once per process start on a background dispatcher; the reconcile
 * pass is idempotent, so an app update simply reinstalls whichever
 * bundled package changed — and uninstalls whichever it retired.
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
                BundledExtensionsInstaller(
                    bundled = bundled,
                    source = AssetPackageSource(),
                    stagingDir = File(context.cacheDir, "bundled-staging"),
                    retiredIds = RETIRED_EXTENSION_IDS,
                ).reconcile(repository)
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
                        .map {
                            BundledPackage(
                                id = it.id,
                                fileName = it.fileName,
                                sha256 = it.sha256,
                                versionCode = it.versionCode,
                            )
                        }
                }
            }.getOrDefault(emptyList())

        /** Reads package bytes straight from the APK's assets directory. */
        private inner class AssetPackageSource : BundledPackageSource {
            override fun open(fileName: String): ByteArray? =
                runCatching { context.assets.open(fileName).use { it.readBytes() } }.getOrNull()
        }

        private companion object {
            val json = Json { ignoreUnknownKeys = true }

            /**
             * Extension ids this APK no longer ships or sanctions —
             * uninstalled on every start wherever they came from.
             *
             * v1.2.22: `cloudimage.wallhere` (bundled since v1.2.21,
             * repo-published before that). The wallhere.com truncation
             * could not be fixed reliably in the field across four
             * provider revisions (v1.1.1 - v1.1.4), and the Bundles
             * repository dropped the package — so the app retires it:
             * upgrading devices lose the dead source instead of keeping
             * a broken one.
             */
            val RETIRED_EXTENSION_IDS =
                setOf(
                    "cloudimage.wallhere",
                )
        }
    }
