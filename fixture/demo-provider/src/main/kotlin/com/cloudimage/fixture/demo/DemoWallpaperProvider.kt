package com.cloudimage.fixture.demo

import com.cloudimage.provider.api.Capability
import com.cloudimage.provider.api.Filters
import com.cloudimage.provider.api.HomeSection
import com.cloudimage.provider.api.Page
import com.cloudimage.provider.api.ProviderHttpClient
import com.cloudimage.provider.api.ProviderMeta
import com.cloudimage.provider.api.ProviderSettings
import com.cloudimage.provider.api.Wallpaper
import com.cloudimage.provider.api.WallpaperDetails
import com.cloudimage.provider.api.WallpaperProvider

/**
 * A compiled provider used as the payload of the extension engine's tests.
 *
 * It deliberately exercises the parts of the contract the engine must
 * prove: it stores the client from [configure] and its pages are derived
 * from a live call through that client, so a test that sees its data
 * knows the HTTP facade really crossed the classloader boundary.
 */
class DemoWallpaperProvider : WallpaperProvider {
    private var httpClient: ProviderHttpClient? = null

    override val meta =
        ProviderMeta(
            id = ID,
            name = "Demo Walls",
            versionName = "1.0.0",
            author = "Cloudimage",
            description = "Fixture provider used by the extension engine tests.",
        )

    override val capabilities: Set<Capability> = setOf(Capability.POPULAR, Capability.SEARCH, Capability.TAGS)

    override fun configure(
        client: ProviderHttpClient,
        settings: ProviderSettings,
    ) {
        httpClient = client
    }

    override suspend fun popular(
        page: Int,
        filters: com.cloudimage.provider.api.Filters,
    ): Result<Page> = catalog(page)

    override suspend fun search(
        query: String,
        page: Int,
        filters: com.cloudimage.provider.api.Filters,
    ): Result<Page> =
        if (query.isBlank()) {
            Result.failure(IllegalArgumentException("blank query"))
        } else {
            catalog(page)
        }

    private suspend fun catalog(page: Int): Result<Page> {
        val client =
            httpClient
                ?: return Result.failure(IllegalStateException("configure() was not called"))
        val response = client.get("demo://catalog/$page")
        if (!response.isSuccessful) {
            return Result.failure(IllegalStateException("HTTP ${response.statusCode}"))
        }
        val wallpaper =
            Wallpaper(
                id = response.bodyText.trim(),
                providerId = ID,
                thumbUrl = "demo://thumb",
                fullUrl = "demo://full",
                title = "Demo wallpaper",
                // Tags ride along so the v1.0.9 "More like this" gate has
                // something to chew on across the classloader boundary.
                tags = listOf("demo", "sunset"),
            )
        return Result.success(Page(wallpapers = listOf(wallpaper), nextPage = if (page < 2) page + 1 else null))
    }

    override suspend fun details(id: String): Result<WallpaperDetails> =
        Result.failure(UnsupportedOperationException("demo fixture has no details"))

    override suspend fun random(): Result<List<Wallpaper>> = Result.success(emptyList())

    /**
     * Exercises the v1.0.9 side of the contract across the classloader
     * boundary: a provider that overrides the default with two named
     * sections, one carrying a filter preset. The engine's tests load this
     * fixture through a real classloader and assert what comes back.
     */
    override suspend fun sections(): List<HomeSection> =
        listOf(
            HomeSection(id = "first", title = "Demo First"),
            HomeSection(id = "second", title = "Demo Second", filters = Filters.of("sorting" to "date")),
        )

    /**
     * The other v1.0.9 default: a provider that answers tag suggestions
     * from its own vocabulary, prefix-matched — proves the second additive
     * method also binds across the classloader boundary in both directions.
     */
    override suspend fun suggestTags(query: String): Result<List<String>> =
        Result.success(
            listOf("demo", "demo walls", "sunset", "sample art").filter {
                it.startsWith(query, ignoreCase = true)
            },
        )

    private companion object {
        const val ID = "cloudimage.demo"
    }
}
