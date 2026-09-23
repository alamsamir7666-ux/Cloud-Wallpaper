package com.cloudimage.extensions.core

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Zip-level access to extension packages.
 *
 * A package is a zip with `extension.json` at the root next to the payload
 * (`classes.dex` in production packages; the JVM test fixture carries
 * plain .class entries instead, which the URL classloader seam reads).
 */
internal object ExtensionPackages {
    /** Reads the manifest text from the package, or null when the entry is absent. */
    fun readManifestText(packageFile: File): String? =
        ZipFile(packageFile).use { zip ->
            val entry: ZipEntry = zip.getEntry(ExtensionManifest.ENTRY_NAME) ?: return null
            zip.getInputStream(entry).readBytes().decodeToString()
        }
}
