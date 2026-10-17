package com.cloudimage.extensions.core

import java.io.File

/**
 * Reads the extension packages an APK ships with (the app bundles its
 * default provider set in assets so a fresh install has content before
 * the user adds any repo).
 */
fun interface BundledPackageSource {
    /** The package bytes, or null when this APK does not ship [fileName]. */
    fun open(fileName: String): ByteArray?
}

/** One row of the generated `bundled.json` shipped next to the packages. */
data class BundledPackage(
    val id: String,
    val fileName: String,
    val sha256: String,
    /** The package's versionCode — the reconcile pass's ordering signal (v1.2.21). */
    val versionCode: Int,
)

/**
 * Reconciles the bundled set with what is installed (v1.2.21):
 *
 * - a bundled extension that is missing, whose installed package is
 *   unreadable (no manifest to read a version from), or that is
 *   STRICTLY NEWER than the installed copy (an app update shipped a
 *   newer one) is installed;
 * - a same-version bundled package whose bytes differ (a re-shipped
 *   refresh) is reinstalled;
 * - an installed copy that is NEWER than the bundle — the user updated
 *   from a repository — is left exactly as it is: bundling must never
 *   roll an update back. The sha-only equality rule this replaces did
 *   precisely that, quietly, on every start, which is why a bundled
 *   extension could never take a repo update;
 * - an installed extension that is no longer bundled is left alone —
 *   removing user data on upgrade is never the right default.
 *
 * Reconciliation keys on the extension *id* (the engine renames package
 * files on install), comparing the installed manifest's versionCode
 * with the bundled row's and the installed file's checksum with the one
 * recorded in `bundled.json`.
 *
 * The whole pass is idempotent, so it can run on every app start.
 */
class BundledExtensionsInstaller(
    private val bundled: List<BundledPackage>,
    private val source: BundledPackageSource,
    private val stagingDir: File,
) {
    /**
     * Installs every stale or missing bundled package; returns the number
     * of packages written. Failures surface per package and never abort
     * the pass — one broken bundle must not block the others.
     */
    suspend fun reconcile(repository: ExtensionRepository): Int {
        val installed =
            repository.installed.value
                .orEmpty()
                .associateBy { it.id }
        var written = 0
        for (entry in bundled) {
            val current = installed[entry.id]
            val installedCode = current?.manifest?.versionCode
            if (current != null && installedCode != null && installedCode > entry.versionCode) {
                // A repo-installed copy strictly newer than the bundled
                // one stays — the user's update is never rolled back.
                continue
            }
            if (current != null &&
                installedCode == entry.versionCode &&
                current.sha256.equals(entry.sha256, ignoreCase = true)
            ) {
                // Same version, same bytes: up to date.
                continue
            }
            val bytes = source.open(entry.fileName) ?: continue
            stagingDir.mkdirs()
            val staging = File(stagingDir, entry.fileName + ".bundled")
            staging.writeBytes(bytes)
            staging.deleteOnExit()
            when (repository.install(staging, expectedSha256 = entry.sha256)) {
                is InstallResult.Installed -> written++
                is InstallResult.Failed -> Unit
            }
        }
        return written
    }
}
