package com.cloudimage.provider.api

/**
 * Version of the provider extension contract.
 *
 * The app and every extension APK are built against this number:
 * - MAJOR mismatch (e.g. app 2.x, plugin 1.x) → the app skips the plugin
 *   with an explanatory message instead of crashing at load time.
 * - Equal MAJOR → plugin loads even if MINOR differs.
 *
 * Bump this when the API below changes in a breaking way.
 * Finalized in Part 5 (extension engine).
 */
object ProviderApi {
    const val VERSION = 1
}

/** Optional operations a provider can support. Drives UI affordances. */
enum class Capability {
    POPULAR,
    LATEST,
    SEARCH,
    TAGS,
    RANDOM,
    FILTERS,
}

/** Content classification used by the global SFW/NSFW switch (default: SFW). */
enum class ContentRating {
    SFW,
    SKETCHY,
    NSFW,
}

/** Identity and policy metadata for a provider. Declared by the plugin itself. */
data class ProviderMeta(
    val id: String,
    val name: String,
    val versionName: String,
    val contentRating: ContentRating = ContentRating.SFW,
    val language: String = "en",
    val description: String = "",
)

/**
 * A single wallpaper as it appears in grids and lists.
 * Thumbs should be small (grid-sized); [fullUrl] points at the original file.
 */
data class Wallpaper(
    val id: String,
    val providerId: String,
    val thumbUrl: String,
    val fullUrl: String,
    val title: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val tags: List<String> = emptyList(),
    val colors: List<String> = emptyList(),
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
    val sourceUrl: String? = null,
)

/** One page of results. [nextPage] is null when there is nothing more to load. */
data class Page(
    val wallpapers: List<Wallpaper>,
    val nextPage: Int? = null,
) {
    val hasNext: Boolean get() = nextPage != null
}

/**
 * Marker for provider-specific filter payloads (categories, sorting, ratio...).
 * The app treats unknown implementations as opaque and passes them back
 * untouched; Part 5 turns this into the full filter DSL.
 */
interface Filters {
    data object None : Filters
}

/**
 * The extension contract — a Cloudimage plugin is an APK containing exactly
 * one implementation of this interface.
 *
 * Implementations must:
 * - be stateless across calls (the app may cache or pool instances),
 * - use the injected HTTP client they receive in Part 5 (never create their own),
 * - never touch Android UI classes; the app renders everything.
 *
 * All methods return [Result] so a failing source degrades to an empty
 * state instead of taking the feed down with it.
 */
interface WallpaperProvider {
    val meta: ProviderMeta
    val capabilities: Set<Capability>

    suspend fun popular(page: Int = 1): Result<Page>

    suspend fun search(
        query: String,
        page: Int = 1,
        filters: Filters = Filters.None,
    ): Result<Page>

    suspend fun details(id: String): Result<WallpaperDetails>

    suspend fun random(): Result<List<Wallpaper>>
}
