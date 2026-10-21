package com.cloudimage.core.network

import com.cloudimage.core.network.NetworkResult.Failure
import com.cloudimage.core.network.NetworkResult.Success
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The download-size sheet's honesty seam (v1.2.10): the TRUE
 * Content-Length of a file, straight from its host.
 *
 * The size sheet used to state a pixel-derived ESTIMATE as the Original's
 * size — `pixels x 0.5 bytes`, calibrated for q90 JPEGs — and CDNs that
 * compress far leaner made it read as a lie: Pinterest originals run
 * ~0.06 bytes/pixel, so an 1122 x 1402 pin showed "~768 KB" and then
 * downloaded its honest 86 KB self. This seam is the cure: the sheet asks
 * the file's own server how big it is before the user commits.
 *
 * An interface (implemented by [CloudimageHttpClient]) so the preview
 * screen's ViewModel stays fake-able in unit tests — the same seam pattern
 * the saver and applier already ride.
 */
interface FileSizeProber {
    /**
     * HEADs `url` and reports its stated Content-Length. [Success] with a
     * null value means the host answered but stated no usable length —
     * callers keep their estimate rather than guess. Any failure is the
     * usual typed [NetworkResult.Failure].
     */
    suspend fun contentLength(url: String): NetworkResult<Long?>
}

/**
 * A completed HTTP exchange: status code, response headers and raw body
 * bytes. Carried by [CloudimageHttpClient.getRaw] — the seam the extension
 * engine adapts into the plugin-facing HTTP facade.
 */
class HttpPayload(
    val statusCode: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
) {
    /** The body decoded as UTF-8 text; empty for empty bodies. */
    val bodyText: String get() = String(body, Charsets.UTF_8)

    /** HTTP-level success: any 2xx status. */
    val isSuccessful: Boolean get() = statusCode in 200..299
}

/**
 * The single HTTP entry point every provider goes through — built-in providers
 * in-process, and loaded extensions through the injected client facade.
 *
 * Responsibilities:
 * - identify the app to servers with a stable [User-Agent][USER_AGENT],
 * - turn every failure mode into a typed [NetworkResult], never an exception,
 * - parse JSON bodies through the shared, forgiving [Json] configuration,
 * - solve Cloudflare challenges on the app's side: a challenged request
 *   is replayed once under a WebView-earned clearance, and when even a
 *   replay cannot get through — a fingerprint-strict zone, or a challenge
 *   no WebView clearance satisfies — the document itself is fetched
 *   through the WebView (see [cloudflare]).
 */
@Singleton
class CloudimageHttpClient
    @Inject
    constructor(
        private val okHttpClient: OkHttpClient,
        private val json: Json,
        private val cloudflare: CloudflareBypasser = CloudflareBypasser.DISABLED,
    ) : FileSizeProber {
        /**
         * Performs a GET and returns the full raw exchange — status, headers
         * and body — whatever the status code is. This is the seam behind the
         * provider facade; transport failures surface as
         * [NetworkResult.Failure] like everywhere else.
         *
         * A Cloudflare challenge answer no longer ends the exchange — the
         * ladder has three rungs. A clearance already on file for the host
         * rides the first attempt. A challenged request asks [cloudflare]
         * for a fresh clearance and replays under it once. When the replay
         * is STILL challenged — or no clearance could be earned at all —
         * the last rung loads the URL in the WebView and returns the
         * document the browser engine rendered, the one answer a
         * fingerprint-strict zone cannot distinguish from a user. When the
         * WebView cannot produce a page either, the challenge response
         * itself is returned like any other non-2xx: per the facade
         * contract, providers decide how to treat it, and their readable
         * failures travel the app's source-failure banner as before.
         *
         * v1.2.6: a host the engine has already learned is fingerprint-
         * strict skips the theater entirely — no plain attempt, no solve,
         * no replay — and goes straight to the WebView document lane
         * ([CloudflareBypasser.fetchDocument]), which runs invisibly while
         * the host's clearance is alive. That is what stops the app
         * "blinking" between itself and the website on every feed load:
         * the visible ladder ran once, earned the cookie, marked the host;
         * everything after that is silent engine traffic.
         */
        suspend fun getRaw(
            url: String,
            extraHeaders: Map<String, String> = emptyMap(),
        ): NetworkResult<HttpPayload> =
            withContext(Dispatchers.IO) {
                try {
                    var payload: HttpPayload
                    if (cloudflare.isWebViewOnly(url)) {
                        payload = fetchDocumentOnly(url, extraHeaders)
                    } else {
                        val state = cloudflare.bypassStateFor(url)
                        var stale = state
                        payload = executePlainGet(url, extraHeaders, state)
                        if (CloudflareChallenge.isChallenge(payload)) {
                            val bypass = cloudflare.solve(url, staleState = stale)
                            if (bypass != null) {
                                stale = bypass
                                payload = execute(url, extraHeaders, bypass)
                            }
                        }
                        if (CloudflareChallenge.isChallenge(payload)) {
                            payload = fetchThroughWebView(url, extraHeaders, stale, payload)
                        }
                    }
                    Success(payload)
                } catch (e: SocketTimeoutException) {
                    Failure(NetworkError.Timeout)
                } catch (e: IOException) {
                    Failure(NetworkError.Io(e))
                }
            }

        /**
         * The stale-on-error seam (v1.3.1): the last 2xx answer the disk
         * cache holds for this URL, without touching the network —
         * `only-if-cached` short-circuits the exchange entirely, so it
         * works with the network down, the DNS gone, the route dropped.
         *
         * The provider facade calls this exactly when a GET's transport
         * failed ([getRaw] came back [NetworkResult.Failure]): the plugin
         * then receives yesterday's document instead of an exception, the
         * same honest-degradation an offline reader's users expect, and it
         * can only ever be a body the source itself once served — nothing
         * is synthesized, nothing is stored by this app (the cache only
         * holds what a real origin answered).
         *
         * Null when there is no such entry: OkHttp answers 504
         * "Unsatisfiable Request" for a cache miss, and any other oddity
         * (an entry that fails to open, an IOException where a cache
         * should be) degrades the same way — the caller keeps its
         * original failure and reports the transport truth.
         *
         * The request mirrors [execute]'s shape (same User-Agent, same
         * plugin headers) so a `Vary`-sensitive origin's entry matches
         * the one the real request would have written.
         */
        suspend fun getCachedOnly(
            url: String,
            extraHeaders: Map<String, String> = emptyMap(),
        ): HttpPayload? =
            withContext(Dispatchers.IO) {
                if (url.isBlank()) return@withContext null
                try {
                    val request =
                        Request
                            .Builder()
                            .url(url)
                            .header(HEADER_USER_AGENT, USER_AGENT)
                            .cacheControl(CacheControl.Builder().onlyIfCached().build())
                            .apply {
                                for ((name, value) in extraHeaders) {
                                    header(name, value)
                                }
                            }.build()
                    okHttpClient.newCall(request).await().use { response ->
                        if (!response.isSuccessful) {
                            return@withContext null
                        }
                        HttpPayload(
                            statusCode = response.code,
                            headers = response.headers.toMultimap(),
                            body = response.body?.bytes() ?: ByteArray(0),
                        )
                    }
                } catch (e: IOException) {
                    null
                }
            }

        /**
         * The plain GET rung of [getRaw], hardened against transient
         * failures (v1.3.1): up to [MAX_TRANSIENT_RETRIES] extra attempts,
         * spaced by exponential backoff with full jitter, ONLY for the two
         * failure shapes mobile networks actually produce and a retry can
         * genuinely fix —
         *
         * - an [IOException] that is NOT a [SocketTimeoutException] (a
         *   connection reset mid-handover, a brief DNS failure, a dropped
         *   route: fast failures, so an immediate retry costs nothing and
         *   often lands), and
         * - a non-challenge answer in [TRANSIENT_RETRY_STATUSES] — 502/503/
         *   504 (CDN and origin blips) and 429 (rate limit). A Cloudflare
         *   challenge shape is excluded deliberately: the ladder owns
         *   challenges, and re-hammering a challenged zone is bot behavior
         *   that can trigger the very escalation it hopes to avoid.
         *
         * Everything else returns or throws on the first attempt exactly
         * as before. Read timeouts never retry: the request already waited
         * the full [okHttpClient] call timeout, and doubling that wait on
         * a search the user is watching would trade a rare rescue for a
         * certain stall. POST never retries through this seam at all —
         * [executePost] is single-shot, the conservative industry default
         * for non-idempotent verbs.
         *
         * A 429's numeric `Retry-After` (seconds) is honored when present
         * and short (<= [RETRY_AFTER_CAP_MS]); a server that demands a
         * longer pause than that is listened to — no retry — because a
         * rate limiter's answer outranks our eagerness.
         */
        private suspend fun executePlainGet(
            url: String,
            extraHeaders: Map<String, String>,
            bypass: CloudflareBypass?,
        ): HttpPayload {
            var attempt = 0
            while (true) {
                try {
                    val payload = execute(url, extraHeaders, bypass)
                    if (attempt >= MAX_TRANSIENT_RETRIES || !isTransientlyUnavailable(payload)) return payload
                    val delayMillis = transientRetryDelayMs(attempt + 1, retryAfterSecondsOf(payload)) ?: return payload
                    delay(delayMillis)
                    attempt++
                } catch (e: IOException) {
                    if (e is SocketTimeoutException || attempt >= MAX_TRANSIENT_RETRIES) throw e
                    delay(transientRetryDelayMs(attempt + 1)!!)
                    attempt++
                }
            }
        }

        /** The transient answer shapes a bounded retry may legally address. */
        private fun isTransientlyUnavailable(payload: HttpPayload): Boolean =
            !payload.isSuccessful &&
                payload.statusCode in TRANSIENT_RETRY_STATUSES &&
                !CloudflareChallenge.isChallenge(payload)

        /** The numeric `Retry-After` (seconds) a 429 carried, when it did. */
        private fun retryAfterSecondsOf(payload: HttpPayload): Int? =
            payload.headers
                .flatMap { (name, values) -> values.map { name to it } }
                .firstOrNull { (name, _) -> name.equals(HEADER_RETRY_AFTER, ignoreCase = true) }
                ?.second?.trim()?.toIntOrNull()

        /**
         * Performs a POST and returns the raw exchange — the v1.3 lane for
         * sources whose continuation is a POST (zedge.net's GraphQL, whose
         * GET the server answers 405). The ladder mirrors [getRaw] rung for
         * rung, with one difference at the top: the last rung is not a
         * document fetch — an engine page cannot POST a body — but a fetch
         * executed INSIDE a WebView page on the request's origin, the one
         * POST shape a fingerprint-strict zone cannot tell apart from the
         * site's own browser XHR.
         *
         * - a host marked WebView-only skips the replay theater and goes
         *   straight to the in-page fetch, degrading to one plain request
         *   when the script trip fails (the zone's honest answer, the same
         *   degradation [getRaw]'s WebView-only lane gives documents);
         * - every other host POSTs plainly first (a clearance on file
         *   rides it), asks for a fresh clearance when the zone challenges,
         *   replays under it once, and escalates to the in-page fetch when
         *   even the replay stays challenged.
         *
         * The in-page rung loads the ORIGIN ROOT of the request URL — an
         * ordinary content page on the same origin — through the full
         * challenge discipline, then evaluates a fetch-script there. Its
         * answer decodes into the exchange; its failure (no page settled,
         * script never answered, unparseable answer) keeps the challenged
         * response the plain rung already produced, the honest result the
         * caller already had.
         */
        suspend fun postRaw(
            url: String,
            body: String,
            contentType: String,
            extraHeaders: Map<String, String> = emptyMap(),
        ): NetworkResult<HttpPayload> =
            withContext(Dispatchers.IO) {
                try {
                    var payload: HttpPayload
                    if (cloudflare.isWebViewOnly(url)) {
                        payload =
                            postThroughPage(url, body, contentType)
                                ?: executePost(url, body, contentType, extraHeaders, null)
                    } else {
                        val state = cloudflare.bypassStateFor(url)
                        payload = executePost(url, body, contentType, extraHeaders, state)
                        if (CloudflareChallenge.isChallenge(payload)) {
                            val bypass = cloudflare.solve(url, staleState = state)
                            if (bypass != null) {
                                payload = executePost(url, body, contentType, extraHeaders, bypass)
                            }
                        }
                        if (CloudflareChallenge.isChallenge(payload)) {
                            payload = postThroughPage(url, body, contentType) ?: payload
                        }
                    }
                    Success(payload)
                } catch (e: SocketTimeoutException) {
                    Failure(NetworkError.Timeout)
                } catch (e: IOException) {
                    Failure(NetworkError.Io(e))
                }
            }

        /**
         * The in-page fetch rung: load the request URL's origin root in
         * the engine — the full challenge discipline runs first, so the
         * script never executes in a challenge shell — then POST [body] to
         * [url]'s path from INSIDE that page, wearing the page's own
         * cookies, agent and fingerprint. Returns the exchange the script
         * reports, or null when no page settled or the script never
         * answered (the caller degrades to its fallback).
         */
        private suspend fun postThroughPage(
            url: String,
            body: String,
            contentType: String,
        ): HttpPayload? {
            val origin = originRootOf(url) ?: return null
            val answer = cloudflare.evaluateOnPage(origin, fetchScript(url, contentType, body)) ?: return null
            val exchange =
                runCatching { json.parseToJsonElement(answer).jsonObject }.getOrNull() ?: return null
            val status = exchange["status"]?.jsonPrimitive?.intOrNull ?: return null
            val text = exchange["body"]?.jsonPrimitive?.contentOrNull ?: ""
            return HttpPayload(statusCode = status, headers = emptyMap(), body = text.toByteArray())
        }

        /** The origin root — `scheme://host/` — the page a script posts from. */
        private fun originRootOf(url: String): String? = url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}/" }

        /**
         * The script the in-page rung evaluates: a same-origin POST of
         * [body] to [url]'s path, answering `{"status":…,"body":…}` on
         * the page globals the solver polls. Every interpolation rides as
         * a JSON-encoded string literal — JSON's escaping is a safe subset
         * of JavaScript's — so a body or content type carrying quotes,
         * angle brackets or script breaks never escapes its literal.
         */
        internal fun fetchScript(
            url: String,
            contentType: String,
            body: String,
        ): String {
            val httpUrl = url.toHttpUrlOrNull()
            val path =
                if (httpUrl != null) {
                    httpUrl.encodedPath + (httpUrl.query?.let { "?$it" } ?: "")
                } else {
                    url
                }
            val pathLiteral = json.encodeToString(String.serializer(), path)
            val contentTypeLiteral = json.encodeToString(String.serializer(), contentType)
            val bodyLiteral = json.encodeToString(String.serializer(), body)
            return "(function(){" +
                "window.__ciScriptDone=false;" +
                "window.__ciScriptResult=null;" +
                "fetch(" + pathLiteral + ",{method:'POST'," +
                "headers:{'Content-Type':" + contentTypeLiteral + "}," +
                "body:" + bodyLiteral + ",credentials:'same-origin'})" +
                ".then(function(r){return r.text().then(function(t){" +
                "return JSON.stringify({status:r.status,body:t});});})" +
                ".then(function(v){window.__ciScriptResult=v;window.__ciScriptDone=true;})" +
                ".catch(function(e){window.__ciScriptResult=" +
                "JSON.stringify({status:0,body:String(e)});window.__ciScriptDone=true;});" +
                "return 'started';})()"
        }

        /**
         * Runs one POST and buffers the exchange — [execute]'s sibling
         * with a request body. The clearance discipline is identical: a
         * [bypass] overrides the agent (clearance binding wins), its
         * cookies ride the request, and the provider's client-hint headers
         * drop on replayed requests so they cannot contradict the agent
         * the clearance is bound to.
         */
        private suspend fun executePost(
            url: String,
            body: String,
            contentType: String,
            extraHeaders: Map<String, String>,
            bypass: CloudflareBypass?,
        ): HttpPayload {
            if (url.isBlank()) {
                return HttpPayload(statusCode = 404, headers = emptyMap(), body = ByteArray(0))
            }
            val replayHeaders =
                if (bypass == null) {
                    extraHeaders
                } else {
                    extraHeaders.filterKeys { name -> !name.startsWith(CLIENT_HINT_PREFIX, ignoreCase = true) }
                }
            val request =
                Request
                    .Builder()
                    .url(url)
                    .header(HEADER_USER_AGENT, USER_AGENT)
                    .apply {
                        for ((name, value) in replayHeaders) {
                            header(name, value)
                        }
                        if (bypass != null) {
                            header(HEADER_USER_AGENT, bypass.userAgent)
                            header(HEADER_COOKIE, bypass.cookieHeader)
                        }
                        post(RequestBody.create(contentType.toMediaTypeOrNull(), body))
                    }.build()
            return okHttpClient.newCall(request).await().use { response ->
                val bytes = response.body?.bytes() ?: ByteArray(0)
                HttpPayload(
                    statusCode = response.code,
                    headers = response.headers.toMultimap(),
                    body = bytes,
                )
            }
        }

        /**
         * The WebView-only lane: the document straight from the engine, no
         * OkHttp round-trips at all. A failed engine trip degrades to one
         * plain request so the caller still receives the zone's honest
         * answer (its challenge response) instead of a synthesized one.
         */
        private suspend fun fetchDocumentOnly(
            url: String,
            extraHeaders: Map<String, String>,
        ): HttpPayload {
            val page = cloudflare.fetchDocument(url)
            val html = page?.html
            if (html != null) {
                return HttpPayload(
                    statusCode = 200,
                    headers = emptyMap(),
                    body = html.toByteArray(),
                )
            }
            return execute(url, extraHeaders, null)
        }

        /**
         * The ladder's last rung, reached only when the request stayed
         * challenged through a replay: fetch the document through the
         * WebView. The fetched HTML wins outright — and marks the host as
         * WebView-only, because the engine just delivered a document no
         * cookie replay could (the fingerprint-strict shape v1.2.6 learned
         * to route around); a page with no HTML but a fresher clearance
         * (earned while this request waited) gets one more replay under it;
         * anything else falls back to the challenged [fallback] response,
         * the honest result the caller already had.
         */
        private suspend fun fetchThroughWebView(
            url: String,
            extraHeaders: Map<String, String>,
            stale: CloudflareBypass?,
            fallback: HttpPayload,
        ): HttpPayload {
            val page = cloudflare.webViewFetch(url, staleState = stale) ?: return fallback
            if (page.html != null) {
                cloudflare.markWebViewOnly(url)
                return HttpPayload(
                    statusCode = 200,
                    headers = emptyMap(),
                    body = page.html.toByteArray(),
                )
            }
            val clearance = page.clearance ?: return fallback
            val replayed = execute(url, extraHeaders, clearance)
            return if (CloudflareChallenge.isChallenge(replayed)) fallback else replayed
        }

        /**
         * Runs one GET and buffers the exchange. [bypass] — the clearance a
         * request carries when the host has one on file, or a freshly earned
         * one on the replay — is applied LAST: a clearance is bound to the
         * User-Agent that earned it, so it overrides both the host agent and
         * any provider browser UA for that host, and its cookies ride the
         * same request.
         *
         * A replayed request also drops the provider's `sec-ch-ua*` client
         * hints (v1.2.4): those headers claim a specific Chrome version —
         * WallpaperFlare's say 131 — and under a clearance the User-Agent is
         * the WebView identity the cookies were earned with. Client hints
         * contradicting the User-Agent are exactly the kind of incoherence
         * Cloudflare's bot scoring reads, so the replay stays silent about
         * browser versions it cannot honestly claim.
         *
         * [onProgress], when set, streams 2xx bodies in [PROGRESS_CHUNK_BYTES]
         * chunks and reports the running byte count plus the Content-Length
         * when there is one; every other body (error pages, challenges) is
         * buffered as before.
         */
        private suspend fun execute(
            url: String,
            extraHeaders: Map<String, String>,
            bypass: CloudflareBypass?,
            onProgress: ((bytesRead: Long, totalBytes: Long?) -> Unit)? = null,
        ): HttpPayload {
            // A blank URL is not a request OkHttp would accept — its builder
            // throws before any typed failure the callers here can handle.
            // Listings that cannot derive a wallpaper's real file (v1.2.7's
            // wallpaperflare contract: the original's URL is minted by the
            // download page, not derivable) carry one until the detail
            // record resolves it, and this guard answers those races with a
            // synthesized 404 instead of a crash.
            if (url.isBlank()) {
                return HttpPayload(statusCode = 404, headers = emptyMap(), body = ByteArray(0))
            }
            val replayHeaders =
                if (bypass == null) {
                    extraHeaders
                } else {
                    extraHeaders.filterKeys { name -> !name.startsWith(CLIENT_HINT_PREFIX, ignoreCase = true) }
                }
            val request =
                Request
                    .Builder()
                    .url(url)
                    .header(HEADER_USER_AGENT, USER_AGENT)
                    .apply {
                        for ((name, value) in replayHeaders) {
                            header(name, value)
                        }
                        if (bypass != null) {
                            header(HEADER_USER_AGENT, bypass.userAgent)
                            header(HEADER_COOKIE, bypass.cookieHeader)
                        }
                    }.build()
            return okHttpClient.newCall(request).await().use { response ->
                val body = response.body
                val bytes =
                    when {
                        body == null -> ByteArray(0)
                        onProgress != null && response.code in 200..299 -> body.readBytesWithProgress(onProgress)
                        else -> body.bytes()
                    }
                HttpPayload(
                    statusCode = response.code,
                    headers = response.headers.toMultimap(),
                    body = bytes,
                )
            }
        }

        /**
         * Performs a GET and returns the body, failing on non-2xx statuses.
         * Implemented on [getRaw]; [extraHeaders] are appended to the
         * User-Agent the client always sends.
         */
        suspend fun get(
            url: String,
            extraHeaders: Map<String, String> = emptyMap(),
        ): NetworkResult<String> {
            val raw = getRaw(url, extraHeaders)
            return when (raw) {
                is Failure -> raw
                is Success ->
                    if (raw.value.isSuccessful) {
                        Success(raw.value.bodyText)
                    } else {
                        Failure(NetworkError.Http(raw.value.statusCode, url))
                    }
            }
        }

        /**
         * Performs a GET and returns the raw body bytes — for downloads of
         * full-resolution images (apply-as-wallpaper, save-to-gallery, share).
         *
         * Same failure taxonomy as [get]; the body is buffered in memory, which
         * is fine for wallpaper-sized files (single-digit megabytes).
         */
        suspend fun download(url: String): NetworkResult<ByteArray> = download(url) { _, _ -> }

        /**
         * [download] with live byte progress: [onProgress] receives the bytes
         * read so far and the total when the server states one (null when the
         * response carries no usable Content-Length — the caller degrades to an
         * indeterminate indicator).
         *
         * Progress only flows on 2xx bodies: error pages and Cloudflare
         * challenges are tiny and buffered as usual, so a solve-and-replay
         * never emits misleading byte counts.
         *
         * v1.2.7: a host the engine has learned is fingerprint-strict — the
         * [CloudflareBypasser.isWebViewOnly] mark — answers EVERY HTTP-client
         * exchange with a challenge, so the solve rung here is pure theater
         * the user pays for as a challenge dialog flash per save. Such a host
         * skips straight to one plain request: the zone's honest answer is
         * returned (almost always the 403 itself), the same degradation
         * [getRaw]'s WebView-only lane applies to documents — minus the
         * document lane itself, which cannot serve image BYTES (the engine
         * renders pages, it does not stream files). Wallpaper image hosts —
         * `c{N}`/`r{N}.wallpaperflare.com`, public and unchallenged for
         * real-device IPs — never carry the mark, so wallpaper downloads
         * ride the ordinary ladder below.
         */
        suspend fun download(
            url: String,
            onProgress: (bytesRead: Long, totalBytes: Long?) -> Unit,
        ): NetworkResult<ByteArray> =
            withContext(Dispatchers.IO) {
                try {
                    val payload =
                        if (cloudflare.isWebViewOnly(url)) {
                            execute(url, emptyMap(), null, onProgress)
                        } else {
                            val state = cloudflare.bypassStateFor(url)
                            val first = execute(url, emptyMap(), state, onProgress)
                            if (!CloudflareChallenge.isChallenge(first)) {
                                first
                            } else {
                                val bypass = cloudflare.solve(url, staleState = state)
                                if (bypass == null) {
                                    first
                                } else {
                                    execute(url, emptyMap(), bypass, onProgress)
                                }
                            }
                        }
                    if (payload.isSuccessful) {
                        Success(payload.body)
                    } else {
                        Failure(NetworkError.Http(payload.statusCode, url))
                    }
                } catch (e: SocketTimeoutException) {
                    Failure(NetworkError.Timeout)
                } catch (e: IOException) {
                    Failure(NetworkError.Io(e))
                }
            }

        /**
         * HEADs the file and reports its stated Content-Length — the true
         * size the download-size sheet states for the Original (v1.2.10),
         * replacing the pixel-derived estimate that CDNs like Pinterest's
         * (≈0.06 bytes/pixel) made read as a nine-fold lie.
         *
         * Deliberately NOT the Cloudflare ladder: hosts that challenge a
         * plain HEAD would only challenge it harder from a background
         * coroutine, and the sheet degrades to its estimate anyway — the
         * download itself still rides the full ladder. A HEAD that answers
         * 2xx without a usable Content-Length (chunked responses, the
         * bridge proxy's own) is a Success carrying null: the caller keeps
         * the estimate rather than trade it for a different guess.
         */
        override suspend fun contentLength(url: String): NetworkResult<Long?> =
            withContext(Dispatchers.IO) {
                if (url.isBlank()) {
                    return@withContext Failure(NetworkError.Http(404, url))
                }
                try {
                    val request =
                        Request
                            .Builder()
                            .url(url)
                            .head()
                            .header(HEADER_USER_AGENT, USER_AGENT)
                            .build()
                    okHttpClient.newCall(request).await().use { response ->
                        if (!response.isSuccessful) {
                            Failure(NetworkError.Http(response.code, url))
                        } else {
                            val length = response.headers[HEADER_CONTENT_LENGTH]?.toLongOrNull()?.takeIf { it > 0 }
                            Success(length)
                        }
                    }
                } catch (e: SocketTimeoutException) {
                    Failure(NetworkError.Timeout)
                } catch (e: IOException) {
                    Failure(NetworkError.Io(e))
                } catch (e: IllegalArgumentException) {
                    // Request.Builder rejects malformed URLs before any
                    // exchange; the callers' estimate stands.
                    Failure(NetworkError.Http(400, url))
                }
            }

        /** Performs a GET and decodes the JSON body into [T]. */
        suspend fun <T> getJson(
            url: String,
            deserializer: KSerializer<T>,
        ): NetworkResult<T> =
            when (val raw = get(url)) {
                is Failure -> raw
                is Success ->
                    try {
                        Success(json.decodeFromString(deserializer, raw.value))
                    } catch (e: SerializationException) {
                        Failure(NetworkError.Serialization(e))
                    } catch (e: IllegalArgumentException) {
                        Failure(NetworkError.Serialization(e))
                    }
            }

        companion object {
            private const val HEADER_USER_AGENT = "User-Agent"

            /** Where a HEAD reads the file's true size from. */
            private const val HEADER_CONTENT_LENGTH = "Content-Length"

            private const val HEADER_COOKIE = "Cookie"

            /** The client-hint family prefix a clearance replay must not contradict. */
            internal const val CLIENT_HINT_PREFIX = "sec-ch-ua"

            /**
             * How many bytes one progress tick covers — wallpaper-sized
             * files report a few dozen ticks, not one per read.
             */
            internal const val PROGRESS_CHUNK_BYTES = 64L * 1024

            /** Where a 429's own retry scheduling is read from. */
            private const val HEADER_RETRY_AFTER = "Retry-After"

            /**
             * The answer shapes a bounded transient retry may legally
             * address (v1.3.1): gateway/origin blips and rate limiting.
             * 500 is deliberately absent — a deterministic origin bug is
             * not made better by asking twice.
             */
            internal val TRANSIENT_RETRY_STATUSES = setOf(502, 503, 504, 429)

            /** At most two extra attempts — three plain requests total. */
            internal const val MAX_TRANSIENT_RETRIES = 2

            /** Nominal first backoff step; doubles each attempt (400ms, 800ms). */
            internal const val RETRY_BASE_DELAY_MS = 400L

            /**
             * The longest `Retry-After` the retry seam will honor; a server
             * demanding more is telling the truth and is obeyed by not
             * retrying at all.
             */
            internal const val RETRY_AFTER_CAP_MS = 5_000L

            /**
             * Milliseconds to sleep before transient retry [attempt]
             * (1-based), full-jitter exponential backoff: 400ms then 800ms
             * nominal, each spread to [0.5x, 1.5x) so a fleet of clients
             * never thunders in lockstep. A short numeric `Retry-After`
             * wins outright (the server scheduled the retry); a
             * `Retry-After` longer than [RETRY_AFTER_CAP_MS] cancels the
             * retry entirely (null) — the caller keeps the honest answer
             * it already has. Pure and internal so the policy itself is
             * unit-testable without a clock.
             */
            internal fun transientRetryDelayMs(
                attempt: Int,
                retryAfterSeconds: Int? = null,
            ): Long? {
                if (retryAfterSeconds != null) {
                    val requested = retryAfterSeconds * 1000L
                    if (requested > RETRY_AFTER_CAP_MS) return null
                    return requested
                }
                val nominal = RETRY_BASE_DELAY_MS shl (attempt - 1).coerceAtLeast(0)
                val jitter = 0.5 + Math.random() * 0.5
                return (nominal * jitter).toLong()
            }

            /**
             * The descriptive User-Agent every unbranded request from this
             * app should carry. Public (v1.2.9) so the app-wide Coil loader
             * can brand image fetches too — Wikimedia's upload CDN 403s any
             * request without a descriptive agent, which is exactly how the
             * global-search previews of Wikipedia images died while the
             * proxied thumbnails kept working.
             */
            const val USER_AGENT =
                "Cloudimage/1.0 (Android; +https://github.com/alamsamir7666-ux/Cloud-Wallpaper)"
        }
    }

/**
 * Streams [this] body into a byte array, reporting the running count to
 * [onProgress] once per [CloudimageHttpClient.PROGRESS_CHUNK_BYTES] read.
 * The declared Content-Length (when positive) rides along as the total;
 * chunked or unlengthed bodies report a null total and the caller decides
 * how to render that honestly.
 */
private fun okhttp3.ResponseBody.readBytesWithProgress(onProgress: (bytesRead: Long, totalBytes: Long?) -> Unit): ByteArray {
    val total = contentLength().takeIf { it > 0 }
    val source = source()
    val output = okio.Buffer()
    val chunk = okio.Buffer()
    var read = 0L
    while (true) {
        val count = source.read(chunk, CloudimageHttpClient.PROGRESS_CHUNK_BYTES)
        if (count == -1L) break
        read += count
        output.write(chunk, count)
        chunk.clear()
        onProgress(read, total)
    }
    return output.readByteArray()
}

/**
 * Suspends until the OkHttp call completes; cancelling the coroutine cancels
 * the HTTP call. OkHttp ships no coroutine adapter, so we bridge via
 * [suspendCancellableCoroutine] — the standard pattern.
 */
private suspend fun Call.await(): Response =
    suspendCancellableCoroutine { continuation ->
        enqueue(
            object : Callback {
                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    continuation.resume(response)
                }

                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    continuation.resumeWithException(e)
                }
            },
        )
        continuation.invokeOnCancellation { runCatching { cancel() } }
    }
