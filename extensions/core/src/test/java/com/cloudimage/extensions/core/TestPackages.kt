package com.cloudimage.extensions.core

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Builds real extension package zips around the compiled demo-provider
 * fixture jar, mirroring the production package format:
 * `extension.json` at the root next to the payload entries.
 */
object TestPackages {
    const val DEMO_ENTRY_CLASS = "com.cloudimage.fixture.demo.DemoWallpaperProvider"

    fun manifestJson(
        id: String = "cloudimage.demo",
        name: String = "Demo Walls",
        versionName: String = "1.0.0",
        versionCode: Int = 1,
        author: String = "Cloudimage",
        description: String = "Fixture provider.",
        apiVersion: Int = 1,
        entryClass: String = DEMO_ENTRY_CLASS,
    ): String =
        """
        {
          "id": "$id",
          "name": "$name",
          "versionName": "$versionName",
          "versionCode": $versionCode,
          "author": "$author",
          "description": "$description",
          "apiVersion": $apiVersion,
          "entryClass": "$entryClass"
        }
        """.trimIndent()

    /** Writes a package zip — fixture payload entries plus [manifest] — into [dir]. */
    fun packageExtension(
        dir: File,
        manifest: String = manifestJson(),
        fileName: String = "test-extension.zip",
    ): File {
        val payload =
            javaClass.classLoader.getResourceAsStream("demo-provider.jar")
                ?: error("demo-provider.jar fixture is missing from test resources")
        val packageFile = File(dir, fileName)
        ZipOutputStream(packageFile.outputStream().buffered()).use { zip ->
            ZipInputStream(payload.buffered()).use { input ->
                while (true) {
                    val entry: ZipEntry = input.nextEntry ?: break
                    if (entry.isDirectory) {
                        continue
                    }
                    zip.putNextEntry(ZipEntry(entry.name))
                    input.copyTo(zip)
                    zip.closeEntry()
                }
            }
            zip.putNextEntry(ZipEntry(ExtensionManifest.ENTRY_NAME))
            zip.write(manifest.toByteArray())
            zip.closeEntry()
        }
        return packageFile
    }

    /** Writes a payload-less package whose only content is [manifest] — or nothing at all. */
    fun packageManifestOnly(
        dir: File,
        manifest: String? = null,
        fileName: String = "manifest-only.zip",
    ): File {
        val packageFile = File(dir, fileName)
        ZipOutputStream(packageFile.outputStream().buffered()).use { zip ->
            if (manifest != null) {
                zip.putNextEntry(ZipEntry(ExtensionManifest.ENTRY_NAME))
                zip.write(manifest.toByteArray())
                zip.closeEntry()
            } else {
                zip.putNextEntry(ZipEntry("stray-entry.txt"))
                zip.write("no manifest in here".toByteArray())
                zip.closeEntry()
            }
        }
        return packageFile
    }
}
