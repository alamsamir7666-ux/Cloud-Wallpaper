package com.cloudimage.core.data

import com.cloudimage.core.data.repository.ExtensionWallpaperSources
import com.cloudimage.core.data.repository.SourceInfo
import com.cloudimage.core.model.ContentRating
import com.cloudimage.core.model.WallpaperCategory
import com.cloudimage.core.model.WallpaperQuery
import com.cloudimage.core.model.WallpaperSorting
import com.cloudimage.core.network.NetworkError
import com.cloudimage.core.network.NetworkResult
import com.cloudimage.extensions.core.ExtensionManifest
import com.cloudimage.extensions.core.ExtensionRepository
import com.cloudimage.extensions.core.ExtensionStatus
import com.cloudimage.extensions.core.InstallResult
import com.cloudimage.extensions.core.InstalledExtension
import com.cloudimage.extensions.core.LoadResult
import com.cloudimage.extensions.core.ProviderTransportException
import com.cloudimage.provider.api.Capability
import com.cloudimage.provider.api.Filters
import com.cloudimage.provider.api.HomeSection
import com.cloudimage.provider.api.ProviderMeta
import com.cloudimage.provider.api.WallpaperProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import com.cloudimage.provider.api.Page as ProviderPage

/**
 * The sources bridge over a scripted engine: the provider-side of every
 * interaction is a real WallpaperProvider recording the calls it saw.
 */
class ExtensionWallpaperSourcesTest {
    private class RecordingProvider(
        override val meta: ProviderMeta,
        override val capabilities: Set<Capability>,
        private val popularPage: ProviderPage? = null,
        private val searchPage: ProviderPage? = null,
        private val error: Throwable? = null,
        private val sections: List<HomeSection>? = null,
        private val sectionsError: Throwable? = null,
        private val tags: List<String>? = null,
        private val tagsError: Throwable? = null,
    ) : WallpaperProvider {
        val calls = mutableListOf<String>()

        override suspend fun popular(
            page: Int,
            filters: Filters,
        ): Result<ProviderPage> {
            calls += "popular:$page:${describe(filters)}"
            return outcome(popularPage)
        }

        override suspend fun search(
            query: String,
            page: Int,
            filters: Filters,
        ): Result<ProviderPage> {
            calls += "search:$query:$page:${describe(filters)}"
            return outcome(searchPage)
        }

        override suspend fun sections(): List<HomeSection> {
            calls += "sections"
            sectionsError?.let { throw it }
            return sections ?: listOf(HomeSection(id = HomeSection.DEFAULT_ID, title = "Popular"))
        }

        override suspend fun suggestTags(query: String): Result<List<String>> {
            calls += "suggestTags:$query"
            tagsError?.let { return Result.failure(it) }
            return Result.success(tags.orEmpty())
        }

        override suspend fun details(id: String): Result<com.cloudimage.provider.api.WallpaperDetails> =
            Result.failure(UnsupportedOperationException())

        override suspend fun random(): Result<List<com.cloudimage.provider.api.Wallpaper>> = Result.success(emptyList())

        private fun outcome(page: ProviderPage?): Result<ProviderPage> =
            error?.let { Result.failure(it) } ?: Result.success(page ?: ProviderPage(emptyList(), null))

        private fun describe(filters: Filters): String = filters.toString().substringAfter("Filters(").substringBefore(")")
    }

    private class FakeEngine(
        vararg providers: WallpaperProvider,
    ) : ExtensionRepository {
        private val installedState = MutableStateFlow<List<InstalledExtension>?>(null)
        override val installed: StateFlow<List<InstalledExtension>?> = installedState.asStateFlow()

        private val byId = providers.associateBy { it.meta.id }

        fun publish(vararg extensions: InstalledExtension) {
            installedState.value = extensions.toList()
        }

        override suspend fun refresh() {}

        override suspend fun install(
            source: File,
            expectedSha256: String?,
        ): InstallResult =
            InstallResult.Failed(
                com.cloudimage.extensions.core.ExtensionError
                    .NotLoadable(ExtensionStatus.CORRUPTED),
            )

        override suspend fun uninstall(extensionId: String): Boolean = false

        override suspend fun providerFor(extension: InstalledExtension): LoadResult {
            val provider =
                byId[extension.manifest?.id]
                    ?: return LoadResult.Failed(
                        com.cloudimage.extensions.core.ExtensionError.EntryClassMissing(
                            extension.manifest?.entryClass.orEmpty(),
                        ),
                    )
            return LoadResult.Loaded(provider)
        }
    }

    private fun extension(
        id: String,
        name: String = id,
    ): InstalledExtension =
        InstalledExtension(
            id = id,
            fileName = "$id.zip",
            sha256 = "00$id",
            status = ExtensionStatus.READY,
            manifest =
                ExtensionManifest(
                    id = id,
                    name = name,
                    versionName = "1.0.0",
                    versionCode = 1,
                    apiVersion = 1,
                    entryClass = "com.example.$id",
                ),
        )

    private fun sourceWallpaper(
        id: String,
        rating: ContentRating = ContentRating.SFW,
    ): com.cloudimage.provider.api.Wallpaper =
        com.cloudimage.provider.api.Wallpaper(
            id = id,
            providerId = "any",
            thumbUrl = "https://t/$id",
            fullUrl = "https://f/$id",
            contentRating =
                when (rating) {
                    ContentRating.SFW -> com.cloudimage.provider.api.ContentRating.SFW
                    ContentRating.SKETCHY -> com.cloudimage.provider.api.ContentRating.SKETCHY
                    ContentRating.NSFW -> com.cloudimage.provider.api.ContentRating.NSFW
                },
        )

    @Test
    fun `blank query goes to popular with translated filters`() =
        runTest {
            val provider =
                RecordingProvider(
                    meta = meta("cloudimage.wallhaven"),
                    capabilities = setOf(Capability.POPULAR),
                    popularPage = ProviderPage(listOf(sourceWallpaper("w1")), null),
                )
            val engine = FakeEngine(provider)
            engine.publish(extension("cloudimage.wallhaven"))
            val sources = ExtensionWallpaperSources(engine, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

            val result = sources.search(WallpaperQuery(), page = 2)

            assertEquals(
                listOf("w1"),
                (result as NetworkResult.Success)
                    .value.page.wallpapers
                    .map { it.id },
            )
            assertTrue(provider.calls.single().startsWith("popular:2:"))
            val filters = provider.calls.single().substringAfter("popular:2:")
            assertTrue(filters.contains("purity=[sfw]"))
            assertTrue(filters.contains("sorting=[toplist]"))
            assertTrue(!filters.contains("category"))
        }

    @Test
    fun `partial category selection travels as a filter`() =
        runTest {
            val provider =
                RecordingProvider(
                    meta = meta("cloudimage.wallhaven"),
                    capabilities = setOf(Capability.SEARCH, Capability.POPULAR),
                )
            val engine = FakeEngine(provider)
            engine.publish(extension("cloudimage.wallhaven"))
            val sources = ExtensionWallpaperSources(engine, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
            val query = WallpaperQuery(categories = setOf(WallpaperCategory.ANIME))

            sources.search(query, page = 1)

            assertTrue(provider.calls.single().contains("category=[anime]"))
        }

    @Test
    fun `text query goes to search`() =
        runTest {
            val provider =
                RecordingProvider(
                    meta = meta("cloudimage.unsplash"),
                    capabilities = setOf(Capability.SEARCH),
                    searchPage = ProviderPage(listOf(sourceWallpaper("u1")), 2),
                )
            val engine = FakeEngine(provider)
            engine.publish(extension("cloudimage.unsplash"))
            val sources = ExtensionWallpaperSources(engine, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

            sources.search(WallpaperQuery(text = "forest"), page = 1)

            assertTrue(provider.calls.single().startsWith("search:forest:1:"))
        }

    @Test
    fun `sources merge and failing ones are skipped`() =
        runTest {
            val a =
                RecordingProvider(
                    meta = meta("cloudimage.a"),
                    capabilities = setOf(Capability.POPULAR),
                    popularPage = ProviderPage(listOf(sourceWallpaper("a1")), nextPage = 3),
                )
            val b =
                RecordingProvider(
                    meta = meta("cloudimage.b"),
                    capabilities = setOf(Capability.POPULAR),
                    error = IllegalStateException("broken plugin"),
                )
            val engine = FakeEngine(a, b)
            engine.publish(extension("cloudimage.a"), extension("cloudimage.b"))
            val sources = ExtensionWallpaperSources(engine, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

            val result = sources.search(WallpaperQuery(), page = 1) as NetworkResult.Success

            assertEquals(
                listOf("a1"),
                result.value.page.wallpapers
                    .map { it.id },
            )
            assertEquals(3, result.value.page.nextPage)
        }

    @Test
    fun `partial failures ride along with the merged page`() =
        runTest {
            val a =
                RecordingProvider(
                    meta = meta("cloudimage.a"),
                    capabilities = setOf(Capability.POPULAR),
                    popularPage = ProviderPage(listOf(sourceWallpaper("a1")), null),
                )
            val b =
                RecordingProvider(
                    meta = meta("cloudimage.b"),
                    capabilities = setOf(Capability.POPULAR),
                    error = ProviderTransportException(NetworkError.Timeout, "https://example.invalid"),
                )
            val engine = FakeEngine(a, b)
            engine.publish(extension("cloudimage.a"), extension("cloudimage.b"))
            val sources = ExtensionWallpaperSources(engine, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

            val result = sources.search(WallpaperQuery(), page = 1) as NetworkResult.Success

            // The survivor still feeds the grid...
            assertEquals(
                listOf("a1"),
                result.value.page.wallpapers
                    .map { it.id },
            )
            // ...and the failure is named instead of silently skipped (v1.0.9).
            val failure = result.value.sourceFailures.single()
            assertEquals("cloudimage.b", failure.sourceId)
            assertEquals("B", failure.sourceName)
            assertTrue(failure.error is NetworkError.Timeout)
        }

    @Test
    fun `a pinned search never carries partial failures`() =
        runTest {
            val provider =
                RecordingProvider(
                    meta = meta("cloudimage.a"),
                    capabilities = setOf(Capability.POPULAR),
                    popularPage = ProviderPage(listOf(sourceWallpaper("a1")), null),
                )
            val engine = FakeEngine(provider)
            engine.publish(extension("cloudimage.a"))
            val sources = ExtensionWallpaperSources(engine, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

            val result = sources.search(WallpaperQuery(), page = 1, sourceId = "cloudimage.a") as NetworkResult.Success

            assertTrue(result.value.sourceFailures.isEmpty())
        }

    @Test
    fun `all sources failing surfaces a source error`() =
        runTest {
            val provider =
                RecordingProvider(
                    meta = meta("cloudimage.a"),
                    capabilities = setOf(Capability.POPULAR),
                    error =
                        com.cloudimage.provider.api
                            .ProviderHttpException("GET failed"),
                )
            val engine = FakeEngine(provider)
            engine.publish(extension("cloudimage.a"))
            val sources = ExtensionWallpaperSources(engine, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

            val result = sources.search(WallpaperQuery(), page = 1)

            assertTrue(result is NetworkResult.Failure)
            val error = (result as NetworkResult.Failure).error
            assertTrue(error is NetworkError.Source)
            assertTrue((error as NetworkError.Source).reason.contains("GET failed"))
        }

    @Test
    fun `transport failures keep their honest type across the plugin boundary`() =
        runTest {
            val provider =
                RecordingProvider(
                    meta = meta("cloudimage.a"),
                    capabilities = setOf(Capability.POPULAR),
                    error = ProviderTransportException(NetworkError.Timeout, "https://example.invalid"),
                )
            val engine = FakeEngine(provider)
            engine.publish(extension("cloudimage.a"))
            val sources = ExtensionWallpaperSources(engine, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

            val result = sources.search(WallpaperQuery(), page = 1)

            // A real timeout surfaces as a timeout — not as "no connection".
            assertTrue((result as NetworkResult.Failure).error is NetworkError.Timeout)
        }

    @Test
    fun `a plugin that cannot bind its classes is a source error`() =
        runTest {
            val provider =
                RecordingProvider(
                    meta = meta("cloudimage.a"),
                    capabilities = setOf(Capability.POPULAR),
                    error = NoClassDefFoundError("kotlin.Unit"),
                )
            val engine = FakeEngine(provider)
            engine.publish(extension("cloudimage.a"))
            val sources = ExtensionWallpaperSources(engine, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

            val result = sources.search(WallpaperQuery(), page = 1)

            val error = (result as NetworkResult.Failure).error
            assertTrue(error is NetworkError.Source)
            assertTrue((error as NetworkError.Source).reason.contains("bind its classes"))
        }

    @Test
    fun `content ratings are enforced per item`() =
        runTest {
            val provider =
                RecordingProvider(
                    meta = meta("cloudimage.wallhaven"),
                    capabilities = setOf(Capability.POPULAR),
                    popularPage =
                        ProviderPage(
                            listOf(
                                sourceWallpaper("safe", ContentRating.SFW),
                                sourceWallpaper("edgy", ContentRating.SKETCHY),
                            ),
                            null,
                        ),
                )
            val engine = FakeEngine(provider)
            engine.publish(extension("cloudimage.wallhaven"))
            val sources = ExtensionWallpaperSources(engine, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

            val result =
                sources.search(WallpaperQuery(contentRatings = setOf(ContentRating.SFW)), page = 1) as
                    NetworkResult.Success

            assertEquals(
                listOf("safe"),
                result.value.page.wallpapers
                    .map { it.id },
            )
        }

    @Test
    fun `no sources installed is a source error`() =
        runTest {
            val sources = ExtensionWallpaperSources(FakeEngine(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

            val result = sources.search(WallpaperQuery(), page = 1)

            val error = (result as NetworkResult.Failure).error
            assertTrue(error is NetworkError.Source)
            assertTrue((error as NetworkError.Source).reason.contains("no wallpaper sources installed"))
        }

    @Test
    fun `refresh exposes loadable sources`() =
        runTest {
            val provider =
                RecordingProvider(
                    meta = meta("cloudimage.unsplash").copy(requiresApiKey = true),
                    capabilities = setOf(Capability.SEARCH),
                )
            val engine = FakeEngine(provider)
            engine.publish(extension("cloudimage.unsplash"))
            val sources = ExtensionWallpaperSources(engine, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

            sources.refresh()

            assertEquals(
                listOf(SourceInfo("cloudimage.unsplash", "Unsplash", true)),
                sources.sources.value,
            )
        }

    @Test
    fun `refresh records why a source failed to load`() =
        runTest {
            val engine = FakeEngine() // every providerFor misses: EntryClassMissing
            engine.publish(extension("cloudimage.broken"))
            val sources = ExtensionWallpaperSources(engine, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

            sources.refresh()

            val reason = sources.loadFailures.value["cloudimage.broken"]
            assertTrue(reason.orEmpty().contains("entry class com.example.cloudimage.broken is missing"))
            assertTrue(
                sources.sources.value
                    .orEmpty()
                    .isEmpty(),
            )
        }

    @Test
    fun `random sorting sends a seed`() =
        runTest {
            val provider =
                RecordingProvider(meta = meta("cloudimage.a"), capabilities = setOf(Capability.POPULAR))
            val engine = FakeEngine(provider)
            engine.publish(extension("cloudimage.a"))
            val sources = ExtensionWallpaperSources(engine, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

            sources.search(WallpaperQuery(sorting = WallpaperSorting.RANDOM, seed = "abcd1234"), page = 1)

            assertTrue(provider.calls.single().contains("seed=[abcd1234]"))
        }

    @Test
    fun `a pinned source routes the query to that provider only`() =
        runTest {
            val wallhaven =
                RecordingProvider(
                    meta = meta("cloudimage.wallhaven"),
                    capabilities = setOf(Capability.POPULAR),
                    popularPage = ProviderPage(listOf(sourceWallpaper("w1")), null),
                )
            val pexels =
                RecordingProvider(
                    meta = meta("cloudimage.pexels"),
                    capabilities = setOf(Capability.POPULAR),
                    popularPage = ProviderPage(listOf(sourceWallpaper("px1")), null),
                )
            val engine = FakeEngine(wallhaven, pexels)
            engine.publish(extension("cloudimage.wallhaven"), extension("cloudimage.pexels"))
            val sources = ExtensionWallpaperSources(engine, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

            val result =
                sources.search(WallpaperQuery(), page = 1, sourceId = "cloudimage.pexels") as
                    NetworkResult.Success

            assertEquals(
                listOf("px1"),
                result.value.page.wallpapers
                    .map { it.id },
            )
            assertTrue(pexels.calls.single().startsWith("popular:1:"))
            assertTrue(wallhaven.calls.isEmpty())
        }

    @Test
    fun `pinning a source that is not installed fails honestly`() =
        runTest {
            val provider =
                RecordingProvider(meta = meta("cloudimage.wallhaven"), capabilities = setOf(Capability.POPULAR))
            val engine = FakeEngine(provider)
            engine.publish(extension("cloudimage.wallhaven"))
            val sources = ExtensionWallpaperSources(engine, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

            val result = sources.search(WallpaperQuery(), page = 1, sourceId = "cloudimage.gone")

            val error = (result as NetworkResult.Failure).error
            assertTrue(error is NetworkError.Source)
            assertTrue((error as NetworkError.Source).reason.contains("cloudimage.gone"))
        }

    // ---- Tag suggestions (v1.0.9) ----

    @Test
    fun `suggestTags merges only TAGS sources and dedupes case-insensitively`() =
        runTest {
            val wallhaven =
                RecordingProvider(
                    meta = meta("cloudimage.wallhaven"),
                    capabilities = setOf(Capability.POPULAR, Capability.TAGS),
                    tags = listOf("landscape", "Landscape", "anime"),
                )
            val pexels =
                RecordingProvider(
                    meta = meta("cloudimage.pexels"),
                    capabilities = setOf(Capability.POPULAR), // no TAGS: never asked
                    tags = listOf("should-not-appear"),
                )
            val unsplash =
                RecordingProvider(
                    meta = meta("cloudimage.unsplash"),
                    capabilities = setOf(Capability.SEARCH, Capability.TAGS),
                    tags = listOf("space", "Landscape"),
                )
            val engine = FakeEngine(wallhaven, pexels, unsplash)
            engine.publish(extension("cloudimage.wallhaven"), extension("cloudimage.pexels"), extension("cloudimage.unsplash"))
            val sources = ExtensionWallpaperSources(engine, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

            val tags = sources.suggestTags("land")

            assertEquals(listOf("landscape", "anime", "space"), tags)
            // Only the TAGS-declaring sources were asked.
            assertTrue(wallhaven.calls.single().startsWith("suggestTags:land"))
            assertTrue(unsplash.calls.single().startsWith("suggestTags:land"))
            assertTrue(pexels.calls.none { it.startsWith("suggestTags") })
        }

    @Test
    fun `a failing suggestion source contributes nothing`() =
        runTest {
            val a =
                RecordingProvider(
                    meta = meta("cloudimage.a"),
                    capabilities = setOf(Capability.TAGS),
                    tags = listOf("landscape"),
                )
            val broken =
                RecordingProvider(
                    meta = meta("cloudimage.broken"),
                    capabilities = setOf(Capability.TAGS),
                    tagsError = IllegalStateException("tag api down"),
                )
            val engine = FakeEngine(a, broken)
            engine.publish(extension("cloudimage.a"), extension("cloudimage.broken"))
            val sources = ExtensionWallpaperSources(engine, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

            val tags = sources.suggestTags("land")

            // Guidance, not a promise: the failure degrades to nothing.
            assertEquals(listOf("landscape"), tags)
        }

    @Test
    fun `suggestTags respects the browse pin`() =
        runTest {
            val wallhaven =
                RecordingProvider(
                    meta = meta("cloudimage.wallhaven"),
                    capabilities = setOf(Capability.POPULAR, Capability.TAGS),
                    tags = listOf("landscape"),
                )
            val pexels =
                RecordingProvider(
                    meta = meta("cloudimage.pexels"),
                    capabilities = setOf(Capability.POPULAR), // pinned but cannot suggest
                    tags = listOf("should-not-appear"),
                )
            val engine = FakeEngine(wallhaven, pexels)
            engine.publish(extension("cloudimage.wallhaven"), extension("cloudimage.pexels"))
            val sources = ExtensionWallpaperSources(engine, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

            assertEquals(listOf("landscape"), sources.suggestTags("land", sourceId = "cloudimage.wallhaven"))
            assertTrue(sources.suggestTags("land", sourceId = "cloudimage.pexels").isEmpty())
            // The unpinned source was never asked while pinned elsewhere.
            assertTrue(pexels.calls.none { it.startsWith("suggestTags") })
        }

    @Test
    fun `suggestTags is blank-safe`() =
        runTest {
            val provider =
                RecordingProvider(
                    meta = meta("cloudimage.wallhaven"),
                    capabilities = setOf(Capability.TAGS),
                    tags = listOf("landscape"),
                )
            val engine = FakeEngine(provider)
            engine.publish(extension("cloudimage.wallhaven"))
            val sources = ExtensionWallpaperSources(engine, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

            assertTrue(sources.suggestTags("   ").isEmpty())
            assertTrue(provider.calls.none { it.startsWith("suggestTags") })
        }

    // ---- Home sections (v1.0.9) ----

    @Test
    fun `a pinned source lists its full sections with translated queries`() =
        runTest {
            val provider =
                RecordingProvider(
                    meta = meta("cloudimage.wallhaven"),
                    capabilities = setOf(Capability.POPULAR),
                    sections =
                        listOf(
                            HomeSection(id = "trending", title = "Trending", filters = Filters.of("sorting" to "toplist")),
                            HomeSection(id = "anime", title = "Anime", filters = Filters.of("category" to "anime")),
                            HomeSection(id = "latest", title = "Latest", filters = Filters.of("sorting" to "date")),
                        ),
                )
            val engine = FakeEngine(provider)
            engine.publish(extension("cloudimage.wallhaven"))
            val sources = ExtensionWallpaperSources(engine, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

            val rows = (sources.sections("cloudimage.wallhaven") as NetworkResult.Success).value

            assertEquals(listOf("trending", "anime", "latest"), rows.map { it.sectionId })
            assertEquals(WallpaperSorting.TOPLIST, rows[0].query.sorting)
            assertEquals(setOf(WallpaperCategory.ANIME), rows[1].query.categories)
            assertEquals(WallpaperSorting.DATE, rows[2].query.sorting)
            // Content ratings stay the user's business — the section
            // cannot smuggle a purity request past the SFW setting.
            assertEquals(setOf(ContentRating.SFW), rows.map { it.query.contentRatings }.distinct().single())
        }

    @Test
    fun `the merged view shows one primary section per source`() =
        runTest {
            val wallhaven =
                RecordingProvider(
                    meta = meta("cloudimage.wallhaven"),
                    capabilities = setOf(Capability.POPULAR),
                    sections =
                        listOf(
                            HomeSection(id = "trending", title = "Trending"),
                            HomeSection(id = "latest", title = "Latest", filters = Filters.of("sorting" to "date")),
                        ),
                )
            // Does not override sections() — the default Popular row.
            val unsplash = RecordingProvider(meta = meta("cloudimage.unsplash"), capabilities = setOf(Capability.POPULAR))
            val engine = FakeEngine(wallhaven, unsplash)
            engine.publish(extension("cloudimage.wallhaven"), extension("cloudimage.unsplash"))
            val sources = ExtensionWallpaperSources(engine, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

            val rows = (sources.sections(null) as NetworkResult.Success).value

            assertEquals(
                listOf("cloudimage.wallhaven" to "trending", "cloudimage.unsplash" to "popular"),
                rows.map { it.sourceId to it.sectionId },
            )
            // The provider-declared row keeps its identity; the default
            // one is marked so the host can label it with the source name.
            assertFalse(rows[0].isDefault)
            assertTrue(rows[1].isDefault)
        }

    @Test
    fun `a provider that fails sections is skipped when others answer`() =
        runTest {
            val healthy =
                RecordingProvider(
                    meta = meta("cloudimage.wallhaven"),
                    capabilities = setOf(Capability.POPULAR),
                    sections = listOf(HomeSection(id = "trending", title = "Trending")),
                )
            val broken =
                RecordingProvider(
                    meta = meta("cloudimage.broken"),
                    capabilities = setOf(Capability.POPULAR),
                    sectionsError = IllegalStateException("sections exploded"),
                )
            val engine = FakeEngine(healthy, broken)
            engine.publish(extension("cloudimage.wallhaven"), extension("cloudimage.broken"))
            val sources = ExtensionWallpaperSources(engine, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

            val rows = (sources.sections(null) as NetworkResult.Success).value

            assertEquals(listOf("cloudimage.wallhaven"), rows.map { it.sourceId })
        }

    @Test
    fun `every provider failing sections surfaces a source error`() =
        runTest {
            val broken =
                RecordingProvider(
                    meta = meta("cloudimage.broken"),
                    capabilities = setOf(Capability.POPULAR),
                    sectionsError = IllegalStateException("sections exploded"),
                )
            val engine = FakeEngine(broken)
            engine.publish(extension("cloudimage.broken"))
            val sources = ExtensionWallpaperSources(engine, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

            val result = sources.sections(null)

            val error = (result as NetworkResult.Failure).error
            assertTrue(error is NetworkError.Source)
            assertTrue((error as NetworkError.Source).reason.contains("sections exploded"))
        }

    @Test
    fun `pinning sections to an uninstalled source fails honestly`() =
        runTest {
            val provider =
                RecordingProvider(meta = meta("cloudimage.wallhaven"), capabilities = setOf(Capability.POPULAR))
            val engine = FakeEngine(provider)
            engine.publish(extension("cloudimage.wallhaven"))
            val sources = ExtensionWallpaperSources(engine, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

            val result = sources.sections("cloudimage.gone")

            val error = (result as NetworkResult.Failure).error
            assertTrue(error is NetworkError.Source)
            assertTrue((error as NetworkError.Source).reason.contains("cloudimage.gone"))
        }

    @Test
    fun `no ready providers yields an empty section list not an error`() =
        runTest {
            val sources = ExtensionWallpaperSources(FakeEngine(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

            val result = sources.sections(null)

            assertTrue(result is NetworkResult.Success)
            assertTrue((result as NetworkResult.Success).value.isEmpty())
        }

    @Test
    fun `unknown filter values are dropped and purity is never read`() =
        runTest {
            val provider =
                RecordingProvider(
                    meta = meta("cloudimage.wallhaven"),
                    capabilities = setOf(Capability.POPULAR),
                    sections =
                        listOf(
                            HomeSection(
                                id = "weird",
                                title = "Weird",
                                filters = Filters.of("category" to "spaceships", "purity" to "nsfw", "sorting" to "random"),
                            ),
                        ),
                )
            val engine = FakeEngine(provider)
            engine.publish(extension("cloudimage.wallhaven"))
            val sources = ExtensionWallpaperSources(engine, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

            val row = (sources.sections("cloudimage.wallhaven") as NetworkResult.Success).value.single()

            assertEquals(WallpaperCategory.entries.toSet(), row.query.categories)
            assertEquals(WallpaperSorting.RANDOM, row.query.sorting)
            assertEquals(setOf(ContentRating.SFW), row.query.contentRatings)
        }

    private fun meta(id: String): ProviderMeta =
        ProviderMeta(
            id = id,
            name = id.substringAfterLast('.').replaceFirstChar { it.uppercase() },
            versionName = "1.0.0",
        )
}
