package com.cloudimage.core.search

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The backend address resolution: the remote override wins when it is
 * fetchable and well-formed, every conceivable failure — HTTP error,
 * garbage body, wrong scheme, blank — silently falls back to the
 * built-in default, AND (v1.2.1) the cached override goes stale with
 * age and on demand through [SearchBackendConfig.invalidate] — so a
 * republished config file heals the next search, not the next process.
 */
class SearchBackendConfigTest {
    private lateinit var server: MockWebServer
    private lateinit var config: SearchBackendConfig
    private val json = Json { ignoreUnknownKeys = true }
    private val fallback = "https://fallback.example.com"
    private var now: Long = 1_000_000L

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun newConfig(): SearchBackendConfig =
        SearchBackendConfig(
            baseClient = OkHttpClient(),
            json = json,
            remoteUrl = server.url("/search-backend.json").toString(),
            defaultBaseUrl = fallback,
            clock = { now },
        )

    @Test
    fun remoteOverrideWinsWhenFetchable() =
        runTest {
            server.enqueue(
                MockResponse().setBody("""{"baseUrl": "https://bridge.example.com"}"""),
            )
            config = newConfig()

            assertEquals("https://bridge.example.com", config.baseUrl())
            assertEquals("/search-backend.json", server.takeRequest().path)
        }

    @Test
    fun httpErrorsFallBackToTheDefault() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(500))
            config = newConfig()

            assertEquals(fallback, config.baseUrl())
        }

    @Test
    fun garbageBodiesFallBackToTheDefault() =
        runTest {
            server.enqueue(MockResponse().setBody("this is not json"))
            config = newConfig()

            assertEquals(fallback, config.baseUrl())
        }

    @Test
    fun nonHttpSchemesAreRejected() =
        runTest {
            server.enqueue(MockResponse().setBody("""{"baseUrl": "ftp://nope.example.com"}"""))
            config = newConfig()

            assertEquals(fallback, config.baseUrl())
        }

    @Test
    fun blankValuesAreRejected() =
        runTest {
            server.enqueue(MockResponse().setBody("""{"baseUrl": "   "}"""))
            config = newConfig()

            assertEquals(fallback, config.baseUrl())
        }

    @Test
    fun unreachableRemotesFallBackToTheDefault() =
        runTest {
            val deadServer = MockWebServer()
            deadServer.start()
            val deadUrl = deadServer.url("/x").toString()
            deadServer.shutdown()
            config =
                SearchBackendConfig(
                    baseClient = OkHttpClient(),
                    json = json,
                    remoteUrl = deadUrl,
                    defaultBaseUrl = fallback,
                    clock = { now },
                )

            assertEquals(fallback, config.baseUrl())
        }

    @Test
    fun freshFetchIsReusedWithinTtlWithoutReFetching() =
        runTest {
            server.enqueue(MockResponse().setBody("""{"baseUrl": "https://bridge.example.com"}"""))
            config = newConfig()

            // First call fetches.
            assertEquals("https://bridge.example.com", config.baseUrl())
            // Move time forward, but stay inside the 10-minute TTL.
            now += 60_000L // 1 minute
            assertEquals("https://bridge.example.com", config.baseUrl())
            now += 60_000L // 2 minutes
            assertEquals("https://bridge.example.com", config.baseUrl())

            // Still only the one fetch.
            assertEquals(1, server.requestCount)
        }

    @Test
    fun staleFetchReFetchesAfterTtl() =
        runTest {
            server.enqueue(MockResponse().setBody("""{"baseUrl": "https://bridge.example.com"}"""))
            server.enqueue(MockResponse().setBody("""{"baseUrl": "https://new-bridge.example.com"}"""))
            config = newConfig()

            // First call fetches the old URL.
            assertEquals("https://bridge.example.com", config.baseUrl())

            // Move past the 10-minute TTL.
            now += 11L * 60L * 1000L

            // Next call re-fetches and picks up the new URL.
            assertEquals("https://new-bridge.example.com", config.baseUrl())
            assertEquals(2, server.requestCount)
        }

    @Test
    fun invalidateForcesReFetchEvenWhenFresh() =
        runTest {
            server.enqueue(MockResponse().setBody("""{"baseUrl": "https://bridge.example.com"}"""))
            server.enqueue(MockResponse().setBody("""{"baseUrl": "https://healed.example.com"}"""))
            config = newConfig()

            // First call fetches; the override is fresh.
            assertEquals("https://bridge.example.com", config.baseUrl())
            assertEquals(1, server.requestCount)

            // Engine reports the bridge dead — invalidate resets the TTL.
            config.invalidate()

            // Even though no clock time has passed, the next call re-fetches.
            assertEquals("https://healed.example.com", config.baseUrl())
            assertEquals(2, server.requestCount)
        }

    @Test
    fun invalidateFallsBackToDefaultUntilTheNextFetchSucceeds() =
        runTest {
            server.enqueue(MockResponse().setBody("""{"baseUrl": "https://bridge.example.com"}"""))
            config = newConfig()
            assertEquals("https://bridge.example.com", config.baseUrl())

            config.invalidate()

            // No further enqueued response — the next fetch fails (4xx),
            // so baseUrl() falls back to the default, not the stale override.
            server.enqueue(MockResponse().setResponseCode(500))
            assertEquals(fallback, config.baseUrl())
        }
}
