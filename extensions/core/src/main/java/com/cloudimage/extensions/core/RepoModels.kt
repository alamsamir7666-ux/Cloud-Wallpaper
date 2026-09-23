package com.cloudimage.extensions.core

import kotlinx.serialization.Serializable

/**
 * A repository the user added, as persisted between runs.
 *
 * [url] always points at the repo's `index.json` (normalized on add); the
 * package files it advertises live next to it — [RepoManager] derives the
 * download URL by stripping the file name. [name] is what the index
 * advertised at add time; it is refreshed whenever the repo is re-fetched.
 */
@Serializable
data class StoredRepo(
    val id: String,
    val url: String,
    val name: String,
    val addedAtMillis: Long,
)

/**
 * One package a repository advertises — the browsing-relevant half of the
 * package manifest, duplicated into the index so the catalog renders
 * without downloading anything.
 *
 * [fileName] is relative to the repo root; [sha256] is the promise the
 * installer verifies before trusting the downloaded bytes.
 */
@Serializable
data class RepoPackageEntry(
    val id: String,
    val fileName: String,
    val sha256: String,
    val sizeBytes: Long = 0L,
    val versionName: String = "",
    val versionCode: Int = 1,
    val apiVersion: Int = 1,
    val author: String = "",
    val description: String = "",
)

/** Wire shape of a repo's `index.json`. */
@Serializable
data class RepoIndexDto(
    val name: String = "",
    val packages: List<RepoPackageEntry> = emptyList(),
)

/**
 * Failure taxonomy for repository operations, mirroring
 * [ExtensionError]'s philosophy: closed, typed, renderable without a
 * `catch (Throwable)`.
 */
sealed interface RepoError {
    /** The entered text is not a usable repo location. */
    data object InvalidUrl : RepoError

    /** The location answered, but the payload is not a valid index. */
    data class BadIndex(val reason: String) : RepoError

    /** The fetch or download failed at the network level. */
    data class Network(val cause: com.cloudimage.core.network.NetworkError) : RepoError
}
