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
import com.cloudimage.provider.api.Capability
import com.cloudimage.provider.api.Filters
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

        override suspend fun details(id: String): Result<com.cloudimage.provider.api.WallpaperDetails> =
            Result.failure(UnsupportedOperationException())

        override suspend fun random(): Result<List<com.cloudimage.provider.api.Wallpaper>> = Result.success(emptyList())

        private fun outcome(page: ProviderPage?): Result<ProviderPage> =
            error?.let { Result.failure(it) } ?: Result.success(page ?: ProviderPage(emptyList(), null))

        private fun describe(filters: Filters): String = filters.toString().substringAfter("Filters(").substringBefore(")")
    }

    private class FakeEngine(vararg providers: WallpaperProvider) : ExtensionRepository {
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
                com.cloudimage.extensions.core.ExtensionError.NotLoadable(ExtensionStatus.CORRUPTED),
            )

        override suspend fun uninstall(extensionId: String): Boolean = false

        override suspend fun providerFor(extension: InstalledExtension): LoadResult {
            val provider =
                byId[extension.manifest?.id]
                    ?: return LoadResult.Failed(
                        com.cloudimage.extensions.core.ExtensionError.EntryClassMissing(""),
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

            assertEquals(listOf("w1"), (result as NetworkResult.Success).value.wallpapers.map { it.id })
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

            assertEquals(listOf("a1"), result.value.wallpapers.map { it.id })
            assertEquals(3, result.value.nextPage)
        }

    @Test
    fun `all sources failing surfaces a network error`() =
        runTest {
            val provider =
                RecordingProvider(
                    meta = meta("cloudimage.a"),
                    capabilities = setOf(Capability.POPULAR),
                    error = com.cloudimage.provider.api.ProviderHttpException("GET failed"),
                )
            val engine = FakeEngine(provider)
            engine.publish(extension("cloudimage.a"))
            val sources = ExtensionWallpaperSources(engine, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

            val result = sources.search(WallpaperQuery(), page = 1)

            assertTrue(result is NetworkResult.Failure)
            assertTrue((result as NetworkResult.Failure).error is NetworkError.Io)
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

            assertEquals(listOf("safe"), result.value.wallpapers.map { it.id })
        }

    @Test
    fun `no sources installed is an io error`() =
        runTest {
            val sources = ExtensionWallpaperSources(FakeEngine(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

            val result = sources.search(WallpaperQuery(), page = 1)

            assertTrue((result as NetworkResult.Failure).error is NetworkError.Io)
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

    private fun meta(id: String): ProviderMeta =
        ProviderMeta(
            id = id,
            name = id.substringAfterLast('.').replaceFirstChar { it.uppercase() },
            versionName = "1.0.0",
        )
}
