package com.cloudimage.extensions.core

import com.cloudimage.core.network.CloudimageHttpClient
import com.cloudimage.core.network.NetworkResult
import com.cloudimage.provider.api.ProviderHttpClient
import com.cloudimage.provider.api.ProviderHttpException
import com.cloudimage.provider.api.ProviderHttpResponse
import javax.inject.Inject
import javax.inject.Singleton

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
                is NetworkResult.Failure -> throw ProviderHttpException("GET $url failed: ${result.error}")
            }
    }
