package com.cloudimage.extensions.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File

/** One row of the on-disk index: which package file belongs to which extension id. */
@Serializable
data class ExtensionIndexEntry(
    val fileName: String,
    val sha256: String,
)

/**
 * Persistence for the extension index (`index.json`).
 *
 * Reads degrade to an empty index when the file is missing or corrupted —
 * the scanner then reports every surviving package as untrusted, which
 * the user resolves by reinstalling. Writes go through a staging file and
 * a rename so a crash never leaves a half-written index behind.
 */
class ExtensionIndex(
    private val file: File,
) {
    fun read(): Map<String, ExtensionIndexEntry> {
        if (!file.exists()) {
            return emptyMap()
        }
        return try {
            json.decodeFromString(Index.serializer(), file.readText()).entries
        } catch (e: SerializationException) {
            emptyMap()
        } catch (e: IllegalArgumentException) {
            emptyMap()
        }
    }

    fun write(entries: Map<String, ExtensionIndexEntry>) {
        val staging = File(file.parentFile, file.name + ".tmp")
        try {
            staging.writeText(json.encodeToString(Index.serializer(), Index(entries)))
            if (!staging.renameTo(file)) {
                if (file.exists()) {
                    file.delete()
                }
                check(staging.renameTo(file)) { "could not replace index ${file.path}" }
            }
        } finally {
            staging.delete()
        }
    }
}

@Serializable
private data class Index(val entries: Map<String, ExtensionIndexEntry>)

private val json = Json { prettyPrint = true }
