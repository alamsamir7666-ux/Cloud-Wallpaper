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
 * fetchable and well-formed, and every conceivable failure — HTTP error,
 * garbage body, wrong scheme, blank — silently falls back to the built-in
 * default, because a config problem must never take search down.
 */
class SearchBackendConfigTest {
    private lateinit var server: MockWebServer
    private lateinit var config: SearchBackendConfig
    private val json = Json { ignoreUnknownKeys = true }
    private val fallback = "https://fallback.example.com"

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
                )

            assertEquals(fallback, config.baseUrl())
        }

    @Test
    fun theFetchRunsOncePerProcess() =
        runTest {
            server.enqueue(MockResponse().setBody("""{"baseUrl": "https://bridge.example.com"}"""))
            config = newConfig()

            config.baseUrl()
            config.baseUrl()
            config.baseUrl()

            assertEquals(1, server.requestCount)
        }
}
