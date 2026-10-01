package com.cloudimage.core.data.repository

import com.cloudimage.core.model.Page
import com.cloudimage.core.model.Wallpaper
import com.cloudimage.core.model.WallpaperDetails
import com.cloudimage.core.model.WallpaperQuery
import com.cloudimage.core.network.NetworkError
import com.cloudimage.core.network.NetworkResult
import kotlinx.coroutines.flow.StateFlow

/**
 * What an installed source can do, mirrored from the provider contract so
 * the UI can gate affordances without touching plugin classes (v1.0.9).
 * [SEARCH] and [TAGS] together drive the detail screen's "More like this"
 * row; [ALBUMS] swaps the browse home for the album paradigm's sidebar UI
 * (v1.1.0); the rest are carried for future surfaces.
 */
enum class SourceCapability {
    POPULAR,
    LATEST,
    SEARCH,
    TAGS,
    RANDOM,
    FILTERS,
    ALBUMS,
}

/**
 * One usable wallpaper source, surfaced to the UI.
 *
 * [requiresApiKey] is true when the provider is useless without a stored
 * key — the extensions screen shows a key entry affordance for those.
 */
data class SourceInfo(
    val id: String,
    val name: String,
    val requiresApiKey: Boolean,
    /** What the provider declared it can do (v1.0.9). */
    val capabilities: Set<SourceCapability> = emptySet(),
)

/**
 * One source that failed to answer a merged search (v1.0.9): who failed
 * and why, in the shared failure taxonomy. The browse grid summarizes
 * these into the per-source failure chip with a retry; a source that
 * fails here degrades to being skipped, exactly as before — this only
 * stops the skip from being silent.
 */
data class SourceFailure(
    val sourceId: String,
    val sourceName: String,
    val error: NetworkError,
)

/**
 * The outcome of a (possibly merged) search: the page plus the sources
 * that failed while answering it (v1.0.9). Pinned searches never carry
 * partial failures — a single source either answers or fails the whole
 * call. A [SearchOutcome] with an empty page and no failures means every
 * source simply had nothing for the query.
 */
data class SearchOutcome(
    val page: Page,
    val sourceFailures: List<SourceFailure> = emptyList(),
)

/**
 * One home-screen section row (v1.0.9): a provider's named feed, resolved
 * to the host query vocabulary so the browse pipeline can load it through
 * the same [WallpaperSources.search] path as everything else.
 */
data class SourceSection(
    /** The provider this row belongs to; rows always load pinned to it. */
    val sourceId: String,
    val sourceName: String,
    /** The provider's own section id, e.g. "trending" or "popular". */
    val sectionId: String,
    /** The section's title as declared by the provider. */
    val title: String,
    /** The section's filter preset, translated to a host query. */
    val query: WallpaperQuery,
    /**
     * True for the generic section every provider gets by default — the
     * host uses it to know a row carries no provider-specific identity and
     * can be labeled with the source name in the merged view.
     */
    val isDefault: Boolean = false,
)

/**
 * One category of an album-style source (v1.1.0), mirrored from the
 * provider contract for the browse sidebar: [iconEmoji] renders inside a
 * tinted circle with [name] underneath, the way the source's own website
 * navigation lists them.
 */
data class SourceCategory(
    val id: String,
    val name: String,
    val iconEmoji: String? = null,
    val coverUrl: String? = null,
)

/**
 * One album of an album-style source (v1.1.0) — a titled collection of
 * wallpapers with a cover and an advisory count, mirrored from the
 * provider contract for the album grids.
 */
data class SourceAlbum(
    val id: String,
    val sourceId: String,
    val title: String,
    val coverUrl: String,
    val wallpaperCount: Int = 0,
)

/**
 * The browse pipeline over whatever providers are installed: the
 * replacement for the Part 3-era built-in Wallhaven repository. Every
 * ready extension is loaded through the engine and queried in parallel;
 * a failing source degrades to being skipped instead of taking the feed
 * down.
 */
interface WallpaperSources {
    /**
     * The usable sources, or null until the first refresh — the UI shows a
     * loading state instead of a misleading "nothing installed" one.
     */
    val sources: StateFlow<List<SourceInfo>?>

    /**
     * Load-failure reasons by source id, filled by every [refresh] — the
     * extension manager renders them as per-source diagnostics so a broken
     * source says why it is broken. Empty when everything loads.
     */
    val loadFailures: StateFlow<Map<String, String>>

    /** Rescans the extensions area; cheap and idempotent. */
    suspend fun refresh()

    /**
     * Queries the sources with [query] at [page] and merges the results.
     *
     * [WallpaperQuery.contentRatings] is translated into the host filter
     * vocabulary (`purity`) and ALSO enforced per item, so a source that
     * ignores the vocabulary cannot leak content the user excluded.
     *
     * [sourceId] pins the query to a single installed source (the v1.0.6
     * browse source switcher); null — the default — queries every ready
     * source in parallel and merges the pages. Sources that fail in the
     * merged mode are skipped and reported through
     * [SearchOutcome.sourceFailures]; only every source failing at once
     * fails the call.
     */
    suspend fun search(
        query: WallpaperQuery,
        page: Int,
        sourceId: String? = null,
    ): NetworkResult<SearchOutcome>

    /**
     * Tag suggestions for the search bar (v1.0.9), merged across the
     * sources in scope that declare `Capability.TAGS` — never a
     * third-party suggest service; suggestions come from the sources the
     * user is already searching, or not at all.
     *
     * Best-effort by design: a failing source contributes nothing and the
     * call never surfaces an error — the suggestion panel is guidance,
     * not a promise. The list is deduped case-insensitively and capped;
     * [sourceId] respects the browse pin the same way [search] does.
     */
    suspend fun suggestTags(
        query: String,
        sourceId: String? = null,
    ): List<String>

    /**
     * The home-screen section rows (v1.0.9).
     *
     * Pinned to [sourceId] (null = the merged view): the pinned source's
     * full section list, or one primary section per ready source — each
     * row loads through [search] pinned to its own source. A provider
     * that fails (or declares nothing) contributes no row; every provider
     * failing surfaces the first failure. Content ratings are
     * deliberately NOT translated — the user's SFW setting owns them.
     */
    suspend fun sections(sourceId: String? = null): NetworkResult<List<SourceSection>>

    /**
     * The source's definitive record for one wallpaper (v1.0.21) — the
     * per-item facts a listing could not carry: the file's TRUE
     * resolution (listings publish thumbnail crops or nothing at all),
     * the download file size, the author, and the page URL.
     *
     * Routed to [Wallpaper.providerId]; a source that is not installed,
     * disabled or failing answers an honest [NetworkError.Source], the
     * same taxonomy [search] uses — the caller decides what a miss means
     * (the preview screen degrades silently to the listing's own
     * values). Sources whose listings already publish true dimensions
     * are not required to be asked at all — callers should fetch only
     * when the listing's dimensions are missing.
     */
    suspend fun details(wallpaper: Wallpaper): NetworkResult<WallpaperDetails>

    /**
     * The album paradigm (v1.1.0): the pinned source's category list for
     * the browse sidebar. Called only for a source that declares
     * [SourceCapability.ALBUMS]; a disabled or missing source answers the
     * same honest [NetworkError.Source] taxonomy [search] uses.
     */
    suspend fun categories(sourceId: String): NetworkResult<List<SourceCategory>>

    /**
     * The album selection the album UI's single "Home" tab shows (v1.1.0) —
     * the source's own homepage. Same gating and honesty rules as
     * [categories].
     */
    suspend fun homeAlbums(sourceId: String): NetworkResult<List<SourceAlbum>>

    /**
     * Every album of one category (v1.1.0), in the source's own order —
     * complete, because album-style sites serve their category pages whole.
     * Same gating and honesty rules as [categories].
     */
    suspend fun albums(
        sourceId: String,
        categoryId: String,
    ): NetworkResult<List<SourceAlbum>>

    /**
     * The wallpapers of one album (v1.1.0) — ordinary feed citizens the
     * preview/apply/download flows treat exactly like search results.
     * Same gating and honesty rules as [categories].
     */
    suspend fun albumWallpapers(
        sourceId: String,
        albumId: String,
    ): NetworkResult<List<Wallpaper>>

    /**
     * Search, album-style (v1.1.0): the source's own search answer — albums
     * the user drills into, not loose wallpapers. The flat [search] keeps
     * serving the merged all-sources feed. Same gating and honesty rules
     * as [categories].
     */
    suspend fun searchAlbums(
        sourceId: String,
        query: String,
    ): NetworkResult<List<SourceAlbum>>
}
