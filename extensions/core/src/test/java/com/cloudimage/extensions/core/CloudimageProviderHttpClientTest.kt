package com.cloudimage.extensions.core

import com.cloudimage.core.network.CloudimageHttpClient
import com.cloudimage.provider.api.ProviderHttpException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CloudimageProviderHttpClientTest {
    private lateinit var server: MockWebServer
    private lateinit var facade: CloudimageProviderHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client =
            CloudimageHttpClient(
                okHttpClient = OkHttpClient(),
                json = Json { ignoreUnknownKeys = true },
            )
        facade = CloudimageProviderHttpClient(client)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun getReturnsStatusHeadersAndBody() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("provider payload")
                    .addHeader("X-Request-Id", "req-7"),
            )

            val response = facade.get(server.url("/v1").toString(), headers = mapOf("X-Provider-Key" to "key-1"))

            assertTrue(response.isSuccessful)
            assertEquals(200, response.statusCode)
            assertEquals("provider payload", response.bodyText)
            assertEquals("req-7", response.header("X-REQUEST-ID"))
            val recorded = server.takeRequest()
            assertEquals("key-1", recorded.getHeader("X-Provider-Key"))
            assertTrue(recorded.getHeader("User-Agent")!!.startsWith("Cloudimage/"))
        }

    @Test
    fun nonSuccessfulStatusesFlowThroughUntouched() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(404).setBody("gone"))

            val response = facade.get(server.url("/missing").toString())

            assertFalse(response.isSuccessful)
            assertEquals(404, response.statusCode)
            assertEquals("gone", response.bodyText)
        }

    @Test
    fun transportFailureThrowsFacadeException() =
        runTest {
            val url = server.url("/down").toString()
            server.shutdown()

            val failure = runCatching { facade.get(url) }.exceptionOrNull()

            assertTrue(failure is ProviderHttpException)
            assertTrue(failure!!.message!!.contains("/down"))
        }
}
