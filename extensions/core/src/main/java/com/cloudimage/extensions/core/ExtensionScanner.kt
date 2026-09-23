package com.cloudimage.extensions.core

import java.io.File

/**
 * Rebuilds the list of installed extensions from disk and index.
 *
 * A row is [ExtensionStatus.READY] only when it is indexed, its checksum
 * still matches and its manifest reads back. Indexed-but-off rows surface
 * as [ExtensionStatus.CORRUPTED]; packages without an index entry surface
 * as [ExtensionStatus.UNTRUSTED] — visible for manual cleanup, never
 * loaded. Stale index entries pointing at missing files are dropped.
 */
class ExtensionScanner(
    private val dirs: ExtensionDirs,
    private val index: ExtensionIndex,
) {
    fun scan(): List<InstalledExtension> {
        val entries = index.read()
        val rows = mutableListOf<InstalledExtension>()
        for ((id, entry) in entries) {
            val file = dirs.packageFile(entry.fileName)
            if (!file.isFile) {
                continue
            }
            rows += rowOf(file, id = id, expectedSha256 = entry.sha256)
        }

        val indexedFiles = entries.values.map { it.fileName }.toSet()
        dirs.packagesDir
            .listFiles()
            ?.filter { it.isFile && it.extension == "zip" && it.name !in indexedFiles }
            ?.forEach { file ->
                rows += rowOf(file, id = null, expectedSha256 = null)
            }
        return rows.sortedBy { it.id }
    }

    private fun rowOf(
        file: File,
        id: String?,
        expectedSha256: String?,
    ): InstalledExtension {
        val sha256 = Sha256.of(file)
        val manifest =
            runCatching {
                ExtensionPackages.readManifestText(file)?.let { ExtensionManifest.parse(it) }
            }.getOrNull()
        return InstalledExtension(
            id = id ?: manifest?.id ?: file.nameWithoutExtension,
            fileName = file.name,
            sha256 = sha256,
            status =
                when {
                    id == null -> ExtensionStatus.UNTRUSTED
                    manifest == null || !sha256.equals(expectedSha256, ignoreCase = true) -> {
                        ExtensionStatus.CORRUPTED
                    }
                    else -> ExtensionStatus.READY
                },
            manifest = manifest,
        )
    }
}
