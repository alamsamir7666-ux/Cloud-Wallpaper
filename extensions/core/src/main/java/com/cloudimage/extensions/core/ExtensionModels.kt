package com.cloudimage.extensions.core

/** Loadability of an installed extension, as judged by the scanner. */
enum class ExtensionStatus {
    /** Indexed, checksum-verified and manifest-readable — safe to load. */
    READY,

    /** Indexed, but the package changed since install or became unreadable. */
    CORRUPTED,

    /** Present on disk without an index entry — visible for cleanup, never loaded. */
    UNTRUSTED,
}

/**
 * One row of the installed-extensions list, rebuilt from disk by
 * [ExtensionScanner] on every [ExtensionRepository.refresh].
 *
 * [manifest] is null when the package could not be read at all — the row
 * stays visible (id falls back to the index entry or file name) so the
 * user can uninstall the broken remains.
 */
data class InstalledExtension(
    val id: String,
    val fileName: String,
    val sha256: String,
    val status: ExtensionStatus,
    val manifest: ExtensionManifest?,
)
