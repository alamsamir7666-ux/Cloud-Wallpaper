package com.cloudimage.extensions.core

import com.cloudimage.core.network.CloudimageHttpClient
import com.cloudimage.core.network.NetworkError
import com.cloudimage.core.network.NetworkResult
import com.cloudimage.provider.api.ProviderHttpClient
import com.cloudimage.provider.api.ProviderHttpException
import com.cloudimage.provider.api.ProviderHttpResponse
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thrown by the host facade on transport failure, carrying the typed
 * [NetworkError] across the plugin boundary so the data layer can tell a
 * real timeout or connectivity loss apart from a source that failed for
 * its own reasons.
 *
 * The provider contract tells plugins to let [ProviderHttpException]
 * propagate — and plugin `runCatching` blocks pass the original exception
 * instance through untouched, so the type survives the trip. A third-party
 * plugin that catches and rethrows generically loses the type; the data
 * layer then reports a plain source failure, which is still honest.
 */
class ProviderTransportException(
    val error: NetworkError,
    url: String,
) : ProviderHttpException("GET $url failed: $error")

/**
 * The bridge between the plugin-facing HTTP facade and the app's shared
 * client: same connection pool, timeouts and User-Agent as everything
 * else in the app.
 *
 * Non-2xx statuses flow through untouched — plugins decide how to treat
 * them — and only transport failures throw, per the facade contract.
 */
@Singleton
class CloudimageProviderHttpClient
    @Inject
    constructor(
        private val client: CloudimageHttpClient,
    ) : ProviderHttpClient {
        override suspend fun get(
            url: String,
            headers: Map<String, String>,
        ): ProviderHttpResponse =
            when (val result = client.getRaw(url, headers)) {
                is NetworkResult.Success -> {
                    val payload = result.value
                    ProviderHttpResponse(
                        statusCode = payload.statusCode,
                        headers = payload.headers,
                        body = payload.body,
                    )
                }
                is NetworkResult.Failure -> throw ProviderTransportException(result.error, url)
            }
    }
