package com.cloudimage.extensions.core

import java.io.File

/**
 * On-disk layout of the extension area:
 * ```
 * extensions/
 * ├── index.json           # what the app installed, with checksums
 * └── packages/<id>.zip    # one self-describing package per extension
 * ```
 *
 * The index is the record of provenance: a package that is not in it was
 * never installed by the app and is reported as [ExtensionStatus.UNTRUSTED].
 */
class ExtensionDirs(
    private val rootDir: File,
) {
    val packagesDir: File get() = File(rootDir, PACKAGES_DIR)

    val indexFile: File get() = File(rootDir, INDEX_FILE)

    fun packageFile(fileName: String): File = File(packagesDir, fileName)

    /** Creates the layout if missing; safe to call repeatedly. */
    fun ensure(): ExtensionDirs {
        rootDir.mkdirs()
        packagesDir.mkdirs()
        return this
    }
}

private const val PACKAGES_DIR = "packages"
private const val INDEX_FILE = "index.json"
