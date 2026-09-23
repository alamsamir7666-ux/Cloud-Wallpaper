package com.cloudimage.core.model

import kotlinx.serialization.Serializable

/**
 * App-internal content classification, mirrored from the provider contract
 * (:provider:api) so the core layers never depend on plugin classes.
 */
enum class ContentRating {
    SFW,
    SKETCHY,
    NSFW,
}

/**
 * A wallpaper as the app stores and renders it.
 *
 * The same wallpaper can come from different providers, so identity is the
 * pair ([providerId], [id]) — never [id] alone.
 */
@Serializable
data class Wallpaper(
    val id: String,
    val providerId: String,
    val thumbUrl: String,
    val fullUrl: String,
    val title: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val sourceUrl: String? = null,
    val contentRating: ContentRating = ContentRating.SFW,
) {
    /** width / height, or null when dimensions are unknown. */
    val aspectRatio: Float?
        get() = if (width != null && height != null && height != 0) width.toFloat() / height else null
}

/** Full detail payload shown on the preview screen. */
data class WallpaperDetails(
    val wallpaper: Wallpaper,
    val author: String? = null,
    val resolution: String? = null,
    val fileSizeBytes: Long? = null,
    val tags: List<String> = emptyList(),
    val description: String? = null,
)

/** One page of results. [nextPage] is a cursor; null means nothing more to load. */
data class Page(
    val wallpapers: List<Wallpaper>,
    val nextPage: Int? = null,
) {
    val hasNext: Boolean get() = nextPage != null

    companion object {
        val EMPTY = Page(wallpapers = emptyList(), nextPage = null)
    }
}

/** A wallpaper saved by the user. */
data class Favorite(
    val wallpaper: Wallpaper,
    val addedAtMillis: Long,
)

/** What the user did with a wallpaper — drives the history feed. */
enum class HistoryAction {
    VIEWED,
    APPLIED,
    DOWNLOADED,
}

/** A history record: the action plus a snapshot of the wallpaper at that time. */
data class HistoryEntry(
    val wallpaper: Wallpaper,
    val action: HistoryAction,
    val atMillis: Long,
)

/** User settings surfaced in Part 7; persisted via Preferences DataStore. */
data class UserPreferences(
    val sfwOnly: Boolean = true,
    val dynamicColorsEnabled: Boolean = true,
    val gridColumns: Int = 2,
)
