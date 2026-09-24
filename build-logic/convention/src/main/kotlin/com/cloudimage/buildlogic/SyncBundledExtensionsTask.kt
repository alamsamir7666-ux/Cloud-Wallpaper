package com.cloudimage.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Stages the bundled wallpaper providers into the app's assets.
 *
 * Copies every packaged (dexed) provider zip handed in through [packages]
 * into [output] and generates a `bundled.json` manifest (ids + sha256s)
 * next to them — a fresh install reconciles the packages through the
 * extension engine at first start.
 *
 * The output is a [DirectoryProperty] (not the legacy `Sync` task) so the
 * app can wire it through the Variant API — AGP 9 rejects Provider
 * instances on the legacy SourceSet DSL, and `addGeneratedSourceDirectory`
 * then carries the task dependency to asset merging itself.
 */
abstract class SyncBundledExtensionsTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val packages: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun sync() {
        val dir = output.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        packages.files.forEach { zip -> zip.copyTo(File(dir, zip.name), overwrite = true) }
        val zips = dir.listFiles { file -> file.extension == "zip" }.orEmpty()
        val manifest =
            zips.joinToString(prefix = "[\n", separator = ",\n", postfix = "\n]") { zip ->
                """  {"id": "${readExtensionId(zip)}", "fileName": "${zip.name}", "sha256": "${sha256(zip)}"}"""
            }
        File(dir, "bundled.json").writeText(manifest)
    }

    /** Reads the `id` field out of the extension.json inside a package zip. */
    private fun readExtensionId(zip: File): String =
        ZipFile(zip).use { archive ->
            val entry = archive.getEntry("extension.json") ?: error("${zip.name} has no extension.json")
            archive.getInputStream(entry).bufferedReader().readText()
        }.substringAfter("\"id\"").substringAfter(':').substringAfter('"').substringBefore('"')

    /** Hex sha256 of a file, matching the engine's checksum format. */
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
