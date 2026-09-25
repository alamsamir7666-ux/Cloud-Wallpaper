package com.cloudimage.extensions.core

import com.cloudimage.provider.api.Capability
import com.cloudimage.provider.api.Filters
import com.cloudimage.provider.api.HomeSection
import com.cloudimage.provider.api.Page
import com.cloudimage.provider.api.ProviderHttpClient
import com.cloudimage.provider.api.ProviderHttpResponse
import com.cloudimage.provider.api.ProviderMeta
import com.cloudimage.provider.api.ProviderSettings
import com.cloudimage.provider.api.Wallpaper
import com.cloudimage.provider.api.WallpaperDetails
import com.cloudimage.provider.api.WallpaperProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URLClassLoader

class ExtensionLoaderTest {
    @get:Rule
    val tempDir = TemporaryFolder()

    private lateinit var dirs: ExtensionDirs
    private lateinit var loader: ExtensionLoader
    private lateinit var httpClient: FakeProviderHttpClient

    @Before
    fun setUp() {
        dirs = ExtensionDirs(File(tempDir.root, "ext")).ensure()
        httpClient =
            FakeProviderHttpClient(
                mapOf(
                    "demo://catalog/1" to response(200, "demo-ok"),
                ),
            )
        loader = ExtensionLoader(dirs, UrlClassLoaderFactory(), httpClient, ProviderSettings { null })
    }

    @Test
    fun loadsConfiguredProviderAndServesPagesThroughTheFacade() =
        runTest {
            val extension = readyRow(TestPackages.manifestJson())

            val result = loader.load(extension)

            assertTrue(result is LoadResult.Loaded)
            val provider = (result as LoadResult.Loaded).provider
            assertEquals("cloudimage.demo", provider.meta.id)

            val page = provider.popular(page = 1).getOrThrow()

            assertEquals("demo-ok", page.wallpapers.single().id)
            assertEquals(listOf("demo://catalog/1"), httpClient.requestedUrls)
        }

    @Test
    fun sectionsOverrideCrossesTheClassloaderBoundary() =
        runTest {
            // The fixture overrides the v1.0.9 default with two named
            // sections — proving a provider compiled against the extended
            // contract answers sections() through the package classloader.
            val extension = readyRow(TestPackages.manifestJson())

            val provider = (loader.load(extension) as LoadResult.Loaded).provider

            val sections = provider.sections()

            assertEquals(listOf("first", "second"), sections.map { it.id })
            assertEquals("Demo First", sections.first().title)
            assertTrue(sections[1].filters.isSelected("sorting", "date"))
        }

    @Test
    fun defaultSectionsSurviveForProvidersThatDoNotOverride() =
        runTest {
            // A provider class that never mentions sections() still answers
            // with the interface default — the binary-compatibility promise
            // that keeps V1 packages loading under the V1.0.9 host.
            val provider =
                object : WallpaperProvider {
                    override val meta =
                        ProviderMeta(
                            id = "cloudimage.legacy",
                            name = "Legacy",
                            versionName = "1.0.0",
                        )

                    override val capabilities: Set<Capability> = setOf(Capability.POPULAR)

                    override suspend fun popular(
                        page: Int,
                        filters: Filters,
                    ): Result<Page> = Result.success(Page(emptyList(), null))

                    override suspend fun search(
                        query: String,
                        page: Int,
                        filters: Filters,
                    ): Result<Page> = Result.success(Page(emptyList(), null))

                    override suspend fun details(id: String): Result<WallpaperDetails> = Result.failure(UnsupportedOperationException())

                    override suspend fun random(): Result<List<Wallpaper>> = Result.success(emptyList())
                }

            val sections = provider.sections()

            assertEquals(listOf(HomeSection.DEFAULT_ID), sections.map { it.id })
            assertTrue(sections.single().filters.isEmpty)
        }

    @Test
    fun providerHttpFailureSurfacesAsFailedResult() =
        runTest {
            val extension = readyRow(TestPackages.manifestJson())
            val failingLoader = ExtensionLoader(dirs, UrlClassLoaderFactory(), errorHttpClient, ProviderSettings { null })

            val page = (failingLoader.load(extension) as LoadResult.Loaded).provider.popular(page = 1)

            assertTrue(page.isFailure)
        }

    @Test
    fun futureApiVersionIsRefusedBeforeLoading() {
        val extension = readyRow(TestPackages.manifestJson(apiVersion = 99))

        val result = loader.load(extension)

        assertTrue(result is LoadResult.Failed)
        assertEquals(
            ExtensionError.UnsupportedApi(declared = 99, supported = com.cloudimage.provider.api.ProviderApi.VERSION),
            (result as LoadResult.Failed).error,
        )
        assertTrue(httpClient.requestedUrls.isEmpty())
    }

    @Test
    fun missingEntryClassFails() {
        val extension = readyRow(TestPackages.manifestJson(entryClass = "com.cloudimage.fixture.demo.NoSuchProvider"))

        val result = loader.load(extension)

        assertEquals(
            ExtensionError.EntryClassMissing(entryClass = "com.cloudimage.fixture.demo.NoSuchProvider"),
            (result as LoadResult.Failed).error,
        )
    }

    @Test
    fun nonProviderEntryClassFails() {
        val extension = readyRow(TestPackages.manifestJson(entryClass = "java.lang.Object"))

        val result = loader.load(extension)

        assertEquals(
            ExtensionError.NotAProvider(entryClass = "java.lang.Object"),
            (result as LoadResult.Failed).error,
        )
    }

    @Test
    fun notReadyExtensionIsRefused() {
        val corrupted =
            TestPackages.packageExtension(
                dirs.packagesDir,
                manifest = TestPackages.manifestJson(),
            )

        val result =
            loader.load(
                InstalledExtension(
                    id = "cloudimage.demo",
                    fileName = corrupted.name,
                    sha256 = Sha256.of(corrupted),
                    status = ExtensionStatus.CORRUPTED,
                    manifest = ExtensionManifest.parse(TestPackages.manifestJson()),
                ),
            )

        assertEquals(
            ExtensionError.NotLoadable(ExtensionStatus.CORRUPTED),
            (result as LoadResult.Failed).error,
        )
    }

    @Test
    fun manifestlessExtensionIsRefused() {
        val result =
            loader.load(
                InstalledExtension(
                    id = "cloudimage.demo",
                    fileName = "cloudimage.demo.zip",
                    sha256 = "irrelevant",
                    status = ExtensionStatus.READY,
                    manifest = null,
                ),
            )

        assertEquals(
            ExtensionError.NotLoadable(ExtensionStatus.READY),
            (result as LoadResult.Failed).error,
        )
    }

    /** Builds a READY row around a package written into the packages dir. */
    private fun readyRow(manifest: String): InstalledExtension {
        val manifestBefore = ExtensionManifest.parse(manifest)
        val packageFile = TestPackages.packageExtension(dirs.packagesDir, manifest = manifest)
        return InstalledExtension(
            id = manifestBefore.id,
            fileName = packageFile.name,
            sha256 = Sha256.of(packageFile),
            status = ExtensionStatus.READY,
            manifest = manifestBefore,
        )
    }

    private fun response(
        status: Int,
        body: String,
    ): ProviderHttpResponse = ProviderHttpResponse(status, emptyMap(), body.toByteArray())

    private val errorHttpClient =
        object : ProviderHttpClient {
            override suspend fun get(
                url: String,
                headers: Map<String, String>,
            ): ProviderHttpResponse = ProviderHttpResponse(500, emptyMap(), "boom".toByteArray())
        }
}

/** JVM stand-in for the production DexClassLoader factory. */
internal class UrlClassLoaderFactory : ExtensionClassLoaderFactory {
    override fun createFor(packageFile: File): ClassLoader = URLClassLoader(arrayOf(packageFile.toURI().toURL()), javaClass.classLoader)
}

/** Scriptable facade the loaded demo provider talks to in tests. */
internal class FakeProviderHttpClient(
    private val responses: Map<String, ProviderHttpResponse>,
) : ProviderHttpClient {
    val requestedUrls = mutableListOf<String>()

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
    ): ProviderHttpResponse {
        requestedUrls += url
        return responses.getValue(url)
    }
}
