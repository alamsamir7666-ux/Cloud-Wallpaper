package com.cloudimage.provider.api

/**
 * Version of the provider extension contract.
 *
 * The app and every extension package are built against this number:
 * - a package declaring a different version is rejected at install time
 *   (and, defensively, again at load time) with an explanatory error
 *   instead of crashing the app;
 * - bump this when the API below changes in a breaking way, and ship the
 *   matching engine update alongside it.
 *
 * Finalized in Part 5 (extension engine).
 */
object ProviderApi {
    const val VERSION = 1

    /**
     * Gate used by the extension engine: a manifest declaring
     * [declaredApiVersion] loads on this host only when the versions match
     * exactly. Older and newer declarations are both refused — a plugin
     * compiled against a different contract cannot be trusted to behave.
     */
    fun isSupported(declaredApiVersion: Int): Boolean = declaredApiVersion == VERSION
}

/** Optional operations a provider can support. Drives UI affordances. */
enum class Capability {
    POPULAR,
    LATEST,
    SEARCH,
    TAGS,
    RANDOM,
    FILTERS,

    /**
     * The provider is album-style (v1.1.0): its content is organized as
     * categories → albums → wallpapers. The host swaps the sectioned home
     * for the album UI — a single "Home" tab fed by
     * [WallpaperProvider.homeAlbums] and a category sidebar fed by
     * [WallpaperProvider.categories] — and routes search through
     * [WallpaperProvider.searchAlbums]. Providers without this capability
     * keep the flat-feed home; the album methods below are never called
     * for them.
     */
    ALBUMS,
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
    val author: String = "",
    val contentRating: ContentRating = ContentRating.SFW,
    val language: String = "en",
    val description: String = "",
    /** True when the provider only serves useful content with a user API key. */
    val requiresApiKey: Boolean = false,
)

/**
 * A single wallpaper as it appears in grids and lists.
 *
 * [thumbUrl] should be small (grid-sized); [fullUrl] points at the original
 * file the app downloads for apply/save/share. [contentRating] lets the host
 * enforce the SFW-only switch per item, whatever the source claims.
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
 * The extension contract — a Cloudimage plugin is a package containing exactly
 * one implementation of this interface, named in the package manifest.
 *
 * Implementations must:
 * - be stateless across calls (the app may cache or pool instances),
 * - route ALL network traffic through the client received in
 *   [configure][WallpaperProvider.configure] — never create their own,
 * - never touch Android UI classes; the app renders everything.
 *
 * All methods return [Result] so a failing source degrades to an empty
 * state instead of taking the feed down with it.
 */
interface WallpaperProvider {
    val meta: ProviderMeta
    val capabilities: Set<Capability>

    /**
     * Called once by the host after instantiation, before any other method.
     *
     * Implementations must store [client] and use it for every request. The
     * host's User-Agent, timeouts, connection pool and debug logging apply
     * uniformly to built-in and third-party sources this way. [settings]
     * carries per-provider configuration — in V1, the user's optional API
     * key, looked up by provider id on every call.
     */
    fun configure(
        client: ProviderHttpClient,
        settings: ProviderSettings,
    ) {
    }

    /**
     * The default feed — what the browse tab shows before any search text.
     *
     * [filters] carries the same host vocabulary as [search]; providers
     * apply what they can express and ignore the rest, so the popular feed
     * stays filtered (categories, purity) on sources that support it.
     */
    suspend fun popular(
        page: Int = 1,
        filters: Filters = Filters.None,
    ): Result<Page>

    suspend fun search(
        query: String,
        page: Int = 1,
        filters: Filters = Filters.None,
    ): Result<Page>

    /**
     * Tag suggestions for the search bar while the user types (v1.0.9).
     *
     * Additive default, exactly like [sections]: providers compiled against
     * the V1 contract do not implement this method and answer nothing, so
     * [ProviderApi.VERSION] stays 1 and old packages keep loading — the
     * host simply gets no suggestions from them. A provider that CAN
     * suggest real tags from its own first-party API overrides this; the
     * host gates the affordance on [Capability.TAGS] and never calls a
     * third-party suggest service — suggestions come from the source the
     * user is already searching, or not at all.
     *
     * [query] is the raw field text, typically a word prefix. Return a
     * short list of tag names — the host merges, dedupes and caps across
     * sources. Providers without the data to answer (e.g. keyless when the
     * tag API needs a key) should return an empty list rather than fire
     * requests destined to fail.
     */
    suspend fun suggestTags(query: String): Result<List<String>> = Result.success(emptyList())

    /**
     * The named feeds the home screen shows as section rows (v1.0.9).
     *
     * Additive default: providers compiled against the V1 contract do not
     * implement this method, and the default below runs through the
     * interface — [ProviderApi.VERSION] stays 1, old packages keep loading,
     * and their home degrades to a single "Popular" row over [popular].
     * Providers that want the CloudStream-style home override it with
     * their own [HomeSection]s, each a titled query preset.
     *
     * Keep it cheap and offline: the host calls this on every feed start.
     */
    suspend fun sections(): List<HomeSection> =
        listOf(
            HomeSection(id = HomeSection.DEFAULT_ID, title = "Popular"),
        )

    suspend fun details(id: String): Result<WallpaperDetails>

    suspend fun random(): Result<List<Wallpaper>>

    /**
     * The album paradigm's category list (v1.1.0) — what the host's
     * category sidebar shows when the feed is pinned to an album-style
     * source ([Capability.ALBUMS]).
     *
     * Additive default, exactly like [sections]: providers compiled against
     * the earlier V1 contract do not implement this, the default below
     * answers nothing, [ProviderApi.VERSION] stays 1 and old packages keep
     * loading. The host never calls it for a source that does not declare
     * [Capability.ALBUMS].
     *
     * Keep it cheap and offline-friendly: the host asks once per album
     * session, and a provider that ships a baked-in category list as a
     * fallback (refreshing it from the first page it fetches) keeps the
     * sidebar alive even when the network is not.
     */
    suspend fun categories(): List<Category> = emptyList()

    /**
     * The album selection the album UI's single "Home" tab shows (v1.1.0) —
     * for a site like WallpaperAccess this is its own homepage: the newest
     * or featured albums.
     *
     * Additive default like [categories]; gated on [Capability.ALBUMS].
     */
    suspend fun homeAlbums(): Result<List<Album>> = Result.success(emptyList())

    /**
     * Every album of one category (v1.1.0), in the source's own order —
     * what the site's category page shows. Album-style sources in the wild
     * serve their category pages complete (no pagination), so the contract
     * returns the whole list; a provider whose site paginates walks its own
     * pages here and answers with the concatenation.
     *
     * Additive default like [categories]; gated on [Capability.ALBUMS].
     */
    suspend fun albums(categoryId: String): Result<List<Album>> = Result.success(emptyList())

    /**
     * The wallpapers of one album (v1.1.0). Same completeness rule as
     * [albums]: the sources this models serve an album's walls in a single
     * page, so the contract returns the whole list.
     *
     * The returned [Wallpaper]s are ordinary feed citizens — the host's
     * preview, apply and download flows treat them exactly like search
     * results, routing [details] and downloads by [Wallpaper.providerId].
     *
     * Additive default like [categories]; gated on [Capability.ALBUMS].
     */
    suspend fun albumWallpapers(albumId: String): Result<List<Wallpaper>> = Result.success(emptyList())

    /**
     * Search, album-style (v1.1.0): what the source's own search answers —
     * albums, not loose wallpapers (WallpaperAccess's "naruto" search is a
     * list of Naruto albums). The host's album UI renders these as album
     * cards the user drills into; the flat [search] keeps serving the
     * merged all-sources feed and older hosts, where an album-style source
     * contributes its wallpapers.
     *
     * Additive default like [categories]; gated on [Capability.ALBUMS].
     */
    suspend fun searchAlbums(query: String): Result<List<Album>> = Result.success(emptyList())
}
