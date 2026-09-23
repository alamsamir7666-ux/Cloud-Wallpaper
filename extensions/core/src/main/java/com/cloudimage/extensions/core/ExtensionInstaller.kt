package com.cloudimage.extensions.core

import com.cloudimage.provider.api.ProviderApi
import java.io.File
import java.io.IOException

/**
 * Installs and removes extension packages.
 *
 * Contract: an installed package always has an index entry whose checksum
 * matches the file on disk — the scanner treats anything else as
 * corruption or an untrusted drop-in. Installation pipeline:
 *
 * 1. verify the checksum against the source's promise (when given),
 * 2. parse and validate the manifest, gate the API version,
 * 3. stage-copy and atomically swap into `packages/`,
 * 4. record id to file+sha in the index.
 *
 * Re-installing an id replaces the previous version in one step.
 */
class ExtensionInstaller(
    private val dirs: ExtensionDirs,
    private val index: ExtensionIndex,
) {
    /** Installs [source]; [expectedSha256] comes from the repository index when known. */
    fun install(
        source: File,
        expectedSha256: String? = null,
    ): InstallResult {
        dirs.ensure()
        val actual = Sha256.of(source)
        if (expectedSha256 != null && !expectedSha256.equals(actual, ignoreCase = true)) {
            return InstallResult.Failed(
                ExtensionError.ChecksumMismatch(expected = expectedSha256, actual = actual),
            )
        }

        val manifestText =
            try {
                ExtensionPackages.readManifestText(source)
            } catch (e: IOException) {
                return InstallResult.Failed(ExtensionError.Io(e))
            }
        if (manifestText == null) {
            return InstallResult.Failed(
                ExtensionError.InvalidManifest("package has no ${ExtensionManifest.ENTRY_NAME} entry"),
            )
        }
        val manifest =
            try {
                ExtensionManifest.parse(manifestText)
            } catch (e: IllegalArgumentException) {
                return InstallResult.Failed(ExtensionError.InvalidManifest(e.message ?: "unreadable manifest"))
            }
        if (!ProviderApi.isSupported(manifest.apiVersion)) {
            return InstallResult.Failed(
                ExtensionError.UnsupportedApi(declared = manifest.apiVersion, supported = ProviderApi.VERSION),
            )
        }

        return try {
            val target = dirs.packageFile(manifest.id + ".zip")
            val staging = File(target.parentFile, target.name + "." + System.nanoTime() + ".tmp")
            source.copyTo(staging, overwrite = true)
            if (target.exists()) {
                target.delete()
            }
            if (!staging.renameTo(target)) {
                throw IOException("could not move ${staging.path} into place")
            }
            index.write(index.read() + (manifest.id to ExtensionIndexEntry(fileName = target.name, sha256 = actual)))
            InstallResult.Installed(manifest = manifest, sha256 = actual)
        } catch (e: IOException) {
            InstallResult.Failed(ExtensionError.Io(e))
        }
    }

    /** Removes the extension from disk and index; false when [extensionId] is unknown. */
    fun uninstall(extensionId: String): Boolean {
        val entries = index.read()
        val entry = entries[extensionId] ?: return false
        val file = dirs.packageFile(entry.fileName)
        val gone = !file.exists() || file.delete()
        if (!gone) {
            return false
        }
        index.write(entries - extensionId)
        return true
    }
}
