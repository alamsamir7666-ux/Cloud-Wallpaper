package com.cloudimage.extensions.core

import com.cloudimage.provider.api.WallpaperProvider

/**
 * Failure taxonomy for every extension-engine operation, kept closed and
 * typed for the same reason as the network layer's NetworkError: the UI
 * branches on it and never needs `catch (Throwable)`.
 */
sealed interface ExtensionError {
    /** The package's extension.json is missing, malformed or violates a constraint. */
    data class InvalidManifest(val reason: String) : ExtensionError

    /** The package bytes do not hash to the value the source promised. */
    data class ChecksumMismatch(val expected: String, val actual: String) : ExtensionError

    /** The package targets a different provider API version than this host implements. */
    data class UnsupportedApi(val declared: Int, val supported: Int) : ExtensionError

    /** The extension is not in a loadable state (corrupted or untrusted on disk). */
    data class NotLoadable(val status: ExtensionStatus) : ExtensionError

    /** The declared entry class could not be found in the package payload. */
    data class EntryClassMissing(val entryClass: String) : ExtensionError

    /** The entry class exists but does not implement the provider contract. */
    data class NotAProvider(val entryClass: String) : ExtensionError

    /** The entry class could not be instantiated (no no-arg constructor, or it threw). */
    data class InstantiationFailed(val cause: Throwable) : ExtensionError

    /** The entry class instantiated but rejected its setup (e.g. configure threw). */
    data class ProviderSetupFailed(val cause: Throwable) : ExtensionError

    /** Filesystem trouble while installing, uninstalling or reading a package. */
    data class Io(val cause: java.io.IOException) : ExtensionError
}

/** Outcome of [ExtensionInstaller.install]. */
sealed interface InstallResult {
    data class Installed(val manifest: ExtensionManifest, val sha256: String) : InstallResult

    data class Failed(val error: ExtensionError) : InstallResult
}

/** Outcome of [ExtensionLoader.load]. */
sealed interface LoadResult {
    data class Loaded(val provider: WallpaperProvider) : LoadResult

    data class Failed(val error: ExtensionError) : LoadResult
}
