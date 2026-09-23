package com.cloudimage.core.data.repository

import com.cloudimage.core.model.Wallpaper

/**
 * Which screen(s) a wallpaper is applied to.
 *
 * Maps onto WallpaperManager flags: HOME = FLAG_SYSTEM, LOCK = FLAG_LOCK,
 * BOTH = the pair combined.
 */
enum class ApplyTarget {
    HOME,
    LOCK,
    BOTH,
}

/** Failure taxonomy for apply-as-wallpaper, already network-aware. */
enum class ApplyError {
    OFFLINE,
    TIMEOUT,
    HTTP,

    /** The bytes arrived but are not a decodable image. */
    DECODE,

    /** The device/user forbids setting this wallpaper target. */
    UNSUPPORTED,

    /** The system WallpaperManager call itself failed. */
    IO,
}

/** Result of applying a wallpaper to a target. */
sealed interface ApplyResult {
    data object Success : ApplyResult

    data class Failure(val error: ApplyError) : ApplyResult
}

/** Failure taxonomy for saving a wallpaper into storage. */
enum class SaveError {
    OFFLINE,
    TIMEOUT,
    HTTP,

    /** Writing the file to disk failed. */
    IO,
}

/**
 * Result of a save operation.
 *
 * [uri] is system-dependent: a `content://` MediaStore uri for gallery
 * saves, an absolute file path for share-file staging — callers decide how
 * to consume it. [fileName] is the bare display name of the written file.
 */
sealed interface SaveResult {
    data class Success(val uri: String, val fileName: String) : SaveResult

    data class Failure(val error: SaveError) : SaveResult
}

/**
 * Applies a wallpaper to the home/lock screen. Implementations download the
 * full-resolution image, decode it and hand it to the system.
 */
interface WallpaperApplier {
    suspend fun apply(
        wallpaper: Wallpaper,
        target: ApplyTarget,
    ): ApplyResult
}

/**
 * Persists wallpapers: into the system gallery (Pictures/Cloudimage) and into
 * a cache staging file for share intents.
 */
interface WallpaperSaver {
    /** Saves the full-resolution image where gallery apps can find it. */
    suspend fun saveToGallery(wallpaper: Wallpaper): SaveResult

    /**
     * Writes the image into an app-private cache dir and returns its absolute
     * path, ready to be wrapped in a FileProvider uri for ACTION_SEND.
     */
    suspend fun prepareShareFile(wallpaper: Wallpaper): SaveResult
}
