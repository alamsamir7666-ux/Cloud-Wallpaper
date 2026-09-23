package com.cloudimage.extensions.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persistence for the user's added repositories (`repos.json`).
 *
 * Same durability story as [ExtensionIndex]: missing or corrupted files
 * degrade to an empty list (the user re-adds), and writes go through a
 * staging file + rename so a crash can never leave a half-written list.
 *
 * A repository's identity is its normalized index URL — re-adding a URL
 * refreshes the stored row instead of duplicating it.
 */
class RepoStore(
    private val file: File,
) {
    fun read(): List<StoredRepo> {
        if (!file.exists()) {
            return emptyList()
        }
        return try {
            json.decodeFromString(Repos.serializer(), file.readText()).repos
        } catch (e: SerializationException) {
            emptyList()
        } catch (e: IllegalArgumentException) {
            emptyList()
        }
    }

    fun find(id: String): StoredRepo? = read().firstOrNull { it.id == id }

    /** Upserts by id (the normalized index URL); returns the stored row. */
    fun upsert(repo: StoredRepo): StoredRepo {
        val existing = read()
        write(existing.filterNot { it.id == repo.id } + repo)
        return repo
    }

    /** Removes by id; false when unknown. */
    fun remove(id: String): Boolean {
        val existing = read()
        if (existing.none { it.id == id }) {
            return false
        }
        write(existing.filterNot { it.id == id })
        return true
    }

    private fun write(repos: List<StoredRepo>) {
        file.parentFile?.mkdirs()
        val staging = File(file.parentFile, file.name + ".tmp")
        try {
            staging.writeText(json.encodeToString(Repos.serializer(), Repos(repos)))
            if (!staging.renameTo(file)) {
                if (file.exists()) {
                    file.delete()
                }
                check(staging.renameTo(file)) { "could not replace repo list ${file.path}" }
            }
        } finally {
            staging.delete()
        }
    }

    @Serializable
    private data class Repos(val repos: List<StoredRepo>)
}

private val json = Json { prettyPrint = true }
