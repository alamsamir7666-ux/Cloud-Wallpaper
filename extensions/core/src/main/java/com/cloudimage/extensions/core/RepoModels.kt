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
 * installer verifies before trusting the downloaded bytes. [categories]
 * (v1.0.20) are the content types the source serves ("anime", "nature"),
 * lower-case and author-declared; a repo whose entries carry none simply
 * renders without the category filter.
 */
@Serializable
data class RepoPackageEntry(
    val id: String,
    val fileName: String,
    val sha256: String,
    val sizeBytes: Long = 0L,
    /** Display name from the manifest (v1.0.20); blank falls back to a
     * capitalized id segment when rendered. */
    val name: String = "",
    val versionName: String = "",
    val versionCode: Int = 1,
    val apiVersion: Int = 1,
    val author: String = "",
    val description: String = "",
    val categories: List<String> = emptyList(),
)

/**
 * One bundle a repository advertises (v1.2.2) — a named, curated group
 * of the repo's own packages, the meta-package pattern: a bundle adds no
 * files of its own, its content IS the list of member package ids
 * ("wallpaper" = 25-40 site extensions, installable as one action).
 *
 * [packageIds] reference the SAME index's [RepoPackageEntry]s; an id the
 * catalog no longer carries renders as an inert "missing" row rather
 * than failing the bundle. Duplicated ids install once.
 */
@Serializable
data class RepoBundleEntry(
    val id: String,
    val name: String = "",
    val description: String = "",
    val packageIds: List<String> = emptyList(),
)

/** Wire shape of a repo's `index.json`. */
@Serializable
data class RepoIndexDto(
    val name: String = "",
    val packages: List<RepoPackageEntry> = emptyList(),
    /** Named groups over [packages] (v1.2.2); older repos declare none. */
    val bundles: List<RepoBundleEntry> = emptyList(),
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
    data class BadIndex(
        val reason: String,
    ) : RepoError

    /** The fetch or download failed at the network level. */
    data class Network(
        val cause: com.cloudimage.core.network.NetworkError,
    ) : RepoError
}
