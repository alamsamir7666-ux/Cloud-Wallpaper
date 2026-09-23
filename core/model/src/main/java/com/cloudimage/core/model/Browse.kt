package com.cloudimage.core.model

/**
 * Content buckets the built-in Wallhaven provider can filter on. Third-party
 * extensions may ignore them, but every V1 provider uses the same vocabulary.
 */
enum class WallpaperCategory {
    GENERAL,
    ANIME,
    PEOPLE,
}

/** How a feed orders its results. */
enum class WallpaperSorting {
    TOPLIST,
    DATE,
    RANDOM,
    RELEVANCE,
}

/**
 * A fully-specified browse request, provider-agnostic on purpose: features
 * build one, repositories translate it into provider-specific API calls.
 *
 * [contentRatings] is what the *user* asked to see; the provider-facing
 * repository clamps it (SFW-only setting, provider capabilities) before any
 * request leaves the app.
 */
data class WallpaperQuery(
    val text: String = "",
    val categories: Set<WallpaperCategory> =
        setOf(WallpaperCategory.GENERAL, WallpaperCategory.ANIME, WallpaperCategory.PEOPLE),
    val contentRatings: Set<ContentRating> = setOf(ContentRating.SFW),
    val sorting: WallpaperSorting = WallpaperSorting.TOPLIST,
    val descending: Boolean = true,
    val seed: String? = null,
) {
    /** True when the query differs from the defaults enough to badge the UI. */
    val isDefault: Boolean
        get() =
            text.isBlank() && contentRatings == setOf(ContentRating.SFW) &&
                sorting == WallpaperSorting.TOPLIST && descending &&
                categories.size == WallpaperCategory.entries.size
}
