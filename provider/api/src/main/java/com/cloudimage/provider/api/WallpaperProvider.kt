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
}
