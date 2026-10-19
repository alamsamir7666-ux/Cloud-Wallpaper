package com.cloudimage.extensions.core

import com.cloudimage.core.network.CloudflareChallenge
import com.cloudimage.core.network.CloudimageHttpClient
import com.cloudimage.core.network.HttpPayload
import com.cloudimage.core.network.NetworkError
import com.cloudimage.core.network.NetworkResult
import com.cloudimage.provider.api.ProviderHttpClient
import com.cloudimage.provider.api.ProviderHttpException
import com.cloudimage.provider.api.ProviderHttpResponse
import java.net.URL
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
 * Thrown by the host facade when the answer a source finally gave is
 * Cloudflare's own furniture — the block page ("Sorry, you have been
 * blocked") or the challenge interstitial ("Just a moment…") — rather
 * than anything the source itself produced (v1.2.13).
 *
 * The wallpapersden.com field report is why this exists: the zone's WAF
 * answers the app's HTTP client with a hard 403 block and its WebView
 * with an interactive Turnstile that refuses to settle, so the ladder
 * exhausts every rung and hands the plugin the original 403 — which the
 * plugin honestly reports as "wallpapersden answered HTTP 403", a
 * sentence that tells the user nothing about what happened or what to
 * do next. The facade is the one place that sees the exchange's full
 * shape (the ladder already ran inside it), so it re-raises the dead
 * end as this exception: the data layer maps it to the same
 * source-failure surface as ever, but the message now says that the
 * site's Cloudflare protection refused the connection, that Retry
 * offers a fresh verification, and that other sources are unaffected.
 *
 * Deliberately narrower than the ladder's own challenge detector
 * ([CloudflareChallenge.isChallenge] fires on any 403/429/503 from a
 * Cloudflare edge, because the WebView may still get through where the
 * HTTP client could not): this throw requires the BODY to be
 * Cloudflare's own page — block or interstitial furniture — so a
 * Cloudflare-fronted API's legitimate 403 ("invalid key", a JSON error)
 * still flows through untouched for the plugin to phrase in its own
 * words. An exception type, not a status branch, so third-party
 * plugins that let [ProviderHttpException] propagate deliver it
 * intact, exactly as [ProviderTransportException] already travels.
 */
class ProviderCloudflareException(
    val host: String,
    val statusCode: Int,
) : ProviderHttpException(
        "$host's Cloudflare protection refused this connection (HTTP $statusCode); " +
            "the verification could not be completed. Retry offers a fresh attempt — " +
            "switching networks (Wi-Fi and mobile data) or trying later sometimes clears it. " +
            "Other sources are unaffected.",
    )

/**
 * The bridge between the plugin-facing HTTP facade and the app's shared
 * client: same connection pool, timeouts and User-Agent as everything
 * else in the app.
 *
 * Non-2xx statuses flow through untouched — plugins decide how to treat
 * them — and only transport failures throw, per the facade contract,
 * with ONE exception (v1.2.13): a non-2xx whose body is Cloudflare's
 * own page (block or interstitial furniture) is the ladder's honest
 * dead end — every rung ran, the zone refused the app — and re-raising
 * it as [ProviderCloudflareException] is the only way the user ever
 * learns WHY a source shows a bare 403. See that class's KDoc for the
 * full story; the body-furniture requirement keeps ordinary API errors
 * flowing through for the plugin to phrase.
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
                    if (isCloudflareFurniture(payload)) {
                        throw ProviderCloudflareException(
                            host = urlHost(url),
                            statusCode = payload.statusCode,
                        )
                    }
                    ProviderHttpResponse(
                        statusCode = payload.statusCode,
                        headers = payload.headers,
                        body = payload.body,
                    )
                }
                is NetworkResult.Failure -> throw ProviderTransportException(result.error, url)
            }

        /**
         * Whether [payload] is Cloudflare's own page rather than the
         * source's answer: a challenge-shaped status carrying block or
         * interstitial furniture in its body, or — v1.2.29 — NO body at
         * all. Both document checks are title-led and conservative — a
         * Cloudflare-fronted API's JSON error matches neither, so it keeps
         * the plain flow-through.
         *
         * The empty-body arm is the wallpapercave.com field report: the
         * zone's bot scoring began answering the app's document requests
         * with a bodyless 403, and since an empty challenge classification
         * can only come from the edge's own headers (the body-marker path
         * needs a body to match), such an answer IS the ladder's dead end —
         * every rung ran inside [CloudimageHttpClient.getRaw], all of them
         * failed, and the body carries nothing a plugin could phrase in
         * its own words. Letting it flow through bought the user the
         * dead "wallpapercave answered HTTP 403" sentence; re-raising it
         * as [ProviderCloudflareException] tells them what actually
         * happened and what Retry actually offers.
         */
        private fun isCloudflareFurniture(payload: HttpPayload): Boolean {
            if (payload.isSuccessful) return false
            if (!CloudflareChallenge.isChallenge(payload)) return false
            val body = payload.bodyText
            if (body.isEmpty()) return true
            return CloudflareChallenge.isHardBlockDocument(body) ||
                CloudflareChallenge.isInterstitialDocument(body)
        }

        /** The URL's host for the message — best effort, URL-shaped on success. */
        private fun urlHost(url: String): String = runCatching { URL(url).host }.getOrDefault(url)
    }
