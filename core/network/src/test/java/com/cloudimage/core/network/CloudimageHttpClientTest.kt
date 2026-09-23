package com.cloudimage.core.network

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CloudimageHttpClientTest {
    @Serializable
    private data class Payload(val id: Int, val name: String)

    private lateinit var server: MockWebServer
    private lateinit var client: CloudimageHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client =
            CloudimageHttpClient(
                okHttpClient = OkHttpClient(),
                json = Json { ignoreUnknownKeys = true },
            )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun getReturnsBodyOnSuccess() =
        runTest {
            server.enqueue(MockResponse().setBody("wallpaper-bytes"))

            val result = client.get(server.url("/file").toString())

            assertEquals(NetworkResult.Success("wallpaper-bytes"), result)
        }

    @Test
    fun getJsonDecodesPayloadAndIgnoresUnknownKeys() =
        runTest {
            server.enqueue(MockResponse().setBody("""{"id": 7, "name": "aurora", "future_field": true}"""))

            val result = client.getJson(server.url("/v1/thing").toString(), Payload.serializer())

            assertEquals(NetworkResult.Success(Payload(id = 7, name = "aurora")), result)
        }

    @Test
    fun getSendsCloudimageUserAgent() =
        runTest {
            server.enqueue(MockResponse().setBody("{}"))

            client.get(server.url("/ping").toString())

            val recorded = server.takeRequest()
            assertEquals(CloudimageHttpClient.USER_AGENT, recorded.getHeader("User-Agent"))
            assertEquals("GET", recorded.method)
        }

    @Test
    fun httpErrorMapsToHttpFailure() =
        runTest {
            val url = server.url("/missing").toString()
            server.enqueue(MockResponse().setResponseCode(500))

            val result = client.get(url)

            assertEquals(NetworkResult.Failure(NetworkError.Http(code = 500, url = url)), result)
        }

    @Test
    fun malformedJsonMapsToSerializationFailure() =
        runTest {
            server.enqueue(MockResponse().setBody("<html>not json</html>"))

            val result = client.getJson(server.url("/bad").toString(), Payload.serializer())

            assertTrue(result is NetworkResult.Failure && result.error is NetworkError.Serialization)
        }

    @Test
    fun unreachableServerMapsToIoFailure() =
        runTest {
            val url = server.url("/nowhere").toString()
            server.shutdown()

            val result = client.get(url)

            assertTrue(result is NetworkResult.Failure && result.error is NetworkError.Io)
        }
}
