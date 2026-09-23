package com.cloudimage.extensions.core

import com.cloudimage.core.network.CloudimageHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * RepoManager over a real MockWebServer and the real engine — the install
 * path included. The only fakes are the clock and the (absent) API keys.
 */
class RepoManagerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var manager: RepoManager
    private lateinit var engine: ExtensionRepository
    private lateinit var repoRoot: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repoRoot = server.url("/repo").toString().trimEnd('/')
        engine = buildEngine()
        manager =
            DefaultRepoManager(
                client = httpClient(),
                repository = engine,
                store = RepoStore(tmp.newFile()),
                downloadsDir = tmp.newFolder(),
                nowMillis = { 1_700_000_000_000 },
            )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // --- URL normalization -------------------------------------------------

    @Test
    fun `root url gains index json`() {
        assertEquals("https://example.com/repo/index.json", manager.normalizeUrl("https://example.com/repo"))
    }

    @Test
    fun `trailing slash is not doubled`() {
        assertEquals("https://example.com/repo/index.json", manager.normalizeUrl("https://example.com/repo/"))
    }

    @Test
    fun `explicit json url survives`() {
        assertEquals("https://example.com/idx.json", manager.normalizeUrl("https://example.com/idx.json"))
    }

    @Test
    fun `garbage is rejected`() {
        assertNull(manager.normalizeUrl("not a url"))
        assertNull(manager.normalizeUrl("ftp://example.com"))
        assertNull(manager.normalizeUrl("   "))
    }

    // --- add ---------------------------------------------------------------

    @Test
    fun `add fetches parses and stores`() =
        runTest {
            enqueueIndex("""{"name":"Official","packages":[]}""")

            val result = manager.add(repoRoot)

            val added = (result as AddRepoResult.Added).repo
            assertEquals("Official", added.name)
            assertEquals("$repoRoot/index.json", added.url)
            assertEquals(listOf(added), manager.repos())
        }

    @Test
    fun `blank index name falls back to the url`() =
        runTest {
            enqueueIndex("""{"packages":[]}""")

            val result = manager.add(repoRoot)

            val added = (result as AddRepoResult.Added).repo
            assertEquals("$repoRoot/index.json", added.name)
        }

    @Test
    fun `re-adding the same url refreshes instead of duplicating`() =
        runTest {
            enqueueIndex("""{"name":"One","packages":[]}""")
            manager.add(repoRoot)
            enqueueIndex("""{"name":"Two","packages":[]}""")
            manager.add(repoRoot)

            val repos = manager.repos()
            assertEquals(1, repos.size)
            assertEquals("Two", repos.single().name)
        }

    @Test
    fun `http error surfaces as network failure`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(404))

            val result = manager.add(repoRoot)

            assertTrue((result as AddRepoResult.Failed).error is RepoError.Network)
        }

    @Test
    fun `non-json body surfaces as bad index`() =
        runTest {
            server.enqueue(MockResponse().setBody("<html>not json</html>"))

            val result = manager.add(repoRoot)

            assertTrue((result as AddRepoResult.Failed).error is RepoError.BadIndex)
        }

    // --- catalog -----------------------------------------------------------

    @Test
    fun `catalog lists advertised packages`() =
        runTest {
            // add() and catalog() each fetch the index.
            enqueueIndex("""{"name":"Official","packages":[${entryJson(sha256 = "00")}]}""")
            enqueueIndex("""{"name":"Official","packages":[${entryJson(sha256 = "00")}]}""")
            val repo = (manager.add(repoRoot) as AddRepoResult.Added).repo

            val catalog = manager.catalog(repo) as RepoIndexResult.Ok

            assertEquals(1, catalog.index.packages.size)
            assertEquals("cloudimage.demo", catalog.index.packages.single().id)
        }

    // --- install -----------------------------------------------------------

    @Test
    fun `install downloads verifies and installs through the engine`() =
        runTest {
            val zip = TestPackages.packageExtension(tmp.newFolder(), fileName = "cloudimage.demo.zip")
            serveRepo(entryJson(sha256 = Sha256.of(zip)), zip.readBytes())

            val repo = (manager.add(repoRoot) as AddRepoResult.Added).repo
            val result = manager.install(repo, (manager.catalog(repo) as RepoIndexResult.Ok).index.packages.single())

            assertTrue(result is InstallResult.Installed)
            val installed = engine.installed.value.orEmpty()
            assertEquals(listOf("cloudimage.demo"), installed.map { it.id })
            assertEquals(ExtensionStatus.READY, installed.single().status)
        }

    @Test
    fun `install with mismatched checksum fails without installing`() =
        runTest {
            val zip = TestPackages.packageExtension(tmp.newFolder(), fileName = "cloudimage.demo.zip")
            serveRepo(entryJson(sha256 = "deadbeef"), zip.readBytes())

            val repo = (manager.add(repoRoot) as AddRepoResult.Added).repo
            val result = manager.install(repo, (manager.catalog(repo) as RepoIndexResult.Ok).index.packages.single())

            val error = (result as InstallResult.Failed).error
            assertTrue(error is ExtensionError.ChecksumMismatch)
            assertTrue(engine.installed.value.orEmpty().isEmpty())
        }

    // --- remove ------------------------------------------------------------

    @Test
    fun `remove drops the repo`() =
        runTest {
            enqueueIndex("""{"name":"Official","packages":[]}""")
            val repo = (manager.add(repoRoot) as AddRepoResult.Added).repo

            assertTrue(manager.remove(repo.id))
            assertFalse(manager.remove(repo.id))
            assertTrue(manager.repos().isEmpty())
        }

    // --- helpers -----------------------------------------------------------

    /** index.json + one package file, dispatched by path. */
    private fun serveRepo(
        entryJson: String,
        packageBytes: ByteArray,
    ) {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when {
                        request.path!!.endsWith("/index.json") ->
                            MockResponse().setBody("""{"name":"Official","packages":[$entryJson]}""")
                        request.path!!.endsWith("/cloudimage.demo.zip") ->
                            MockResponse().setBody(Buffer().write(packageBytes))
                        else -> MockResponse().setResponseCode(404)
                    }
            }
    }

    private fun enqueueIndex(body: String) {
        server.enqueue(MockResponse().setBody(body))
    }

    private fun entryJson(
        id: String = "cloudimage.demo",
        sha256: String,
    ): String =
        """{"id":"$id","fileName":"cloudimage.demo.zip","sha256":"$sha256",""" +
            """"versionName":"1.0.0","versionCode":1,"apiVersion":1}"""

    private fun httpClient() =
        CloudimageHttpClient(
            okHttpClient = OkHttpClient(),
            json = Json { ignoreUnknownKeys = true },
        )

    private fun buildEngine(): ExtensionRepository {
        val dirs = ExtensionDirs(tmp.newFolder()).ensure()
        val index = ExtensionIndex(dirs.indexFile)
        return DefaultExtensionRepository(
            installer = ExtensionInstaller(dirs, index),
            scanner = ExtensionScanner(dirs, index),
            loader =
                ExtensionLoader(
                    dirs,
                    UrlClassLoaderFactory(),
                    CloudimageProviderHttpClient(
                        httpClient(),
                    ),
                    com.cloudimage.provider.api.ProviderSettings {
                        null
                    },
                ),
            ioDispatcher = Dispatchers.Unconfined,
        )
    }
}
