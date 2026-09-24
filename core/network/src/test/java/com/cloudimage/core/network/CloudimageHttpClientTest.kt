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
    private data class Payload(
        val id: Int,
        val name: String,
    )

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

    @Test
    fun downloadReturnsRawBytesOnSuccess() =
        runTest {
            // Binary body: a mix that would break naive String decoding.
            val bytes =
                byteArrayOf(
                    0x89.toByte(),
                    0x50,
                    0x4E,
                    0x47,
                    0x0D,
                    0x0A,
                    0x1A,
                    0x0A,
                    0x00,
                    0xFF.toByte(),
                )
            server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))

            val result = client.download(server.url("/full/xy.jpg").toString())

            assertTrue(result is NetworkResult.Success)
            assertTrue((result as NetworkResult.Success).value.contentEquals(bytes))
            val recorded = server.takeRequest()
            assertEquals(CloudimageHttpClient.USER_AGENT, recorded.getHeader("User-Agent"))
        }

    @Test
    fun downloadMapsHttpErrorToFailure() =
        runTest {
            val url = server.url("/full/missing.jpg").toString()
            server.enqueue(MockResponse().setResponseCode(404))

            val result = client.download(url)

            assertEquals(NetworkResult.Failure(NetworkError.Http(code = 404, url = url)), result)
        }

    @Test
    fun getRawSendsExtraHeaders() =
        runTest {
            server.enqueue(MockResponse().setBody("{}"))

            client.getRaw(
                server.url("/provider").toString(),
                extraHeaders = mapOf("X-Provider-Key" to "token-42"),
            )

            val recorded = server.takeRequest()
            assertEquals("token-42", recorded.getHeader("X-Provider-Key"))
            assertEquals(CloudimageHttpClient.USER_AGENT, recorded.getHeader("User-Agent"))
        }

    @Test
    fun getRawReturnsStatusHeadersAndBody() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("payload")
                    .addHeader("X-Request-Id", "abc-123"),
            )

            val result = client.getRaw(server.url("/raw").toString())

            assertTrue(result is NetworkResult.Success)
            val payload = (result as NetworkResult.Success).value
            assertEquals(200, payload.statusCode)
            assertEquals("payload", payload.bodyText)
            assertEquals("abc-123", payload.headers["X-Request-Id"]?.single())
        }

    @Test
    fun getRawPassesNonSuccessfulStatusesThrough() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(404)
                    .setBody("not here"),
            )

            val result = client.getRaw(server.url("/gone").toString())

            assertTrue(result is NetworkResult.Success)
            val payload = (result as NetworkResult.Success).value
            assertEquals(404, payload.statusCode)
            assertEquals("not here", payload.bodyText)
        }
}
