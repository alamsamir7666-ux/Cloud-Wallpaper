package com.cloudimage.core.search

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Where the global search's backend lives.
 *
 * The search engine's real upstream (the z-ai image-search service) is
 * reachable only from inside this app's sandbox — it has no public address
 * a phone could call. The Browser repo system (deployed in the sandbox and
 * kept alive by its boot hook) IS the public bridge: it exposes the search
 * over plain HTTPS so the app can reach it from anywhere.
 *
 * The address is therefore a deployment constant that can change when the
 * sandbox is recycled — which is why it is NOT only baked into the APK.
 * [baseUrl] prefers a remote override fetched from the app's own GitHub
 * repo (see [REMOTE_CONFIG_URL]): editing that one file repoints every
 * installed app instantly, no update required. If the fetch fails for any
 * reason — offline, GitHub down, malformed JSON — the built-in
 * [DEFAULT_BASE_URL] keeps search working exactly as before.
 *
 * A fetched override no longer lives for the whole process (v1.2.1):
 * bridges are re-published without notice, so the fetch goes stale with
 * age ([REFRESH_TTL_MS]) and — the important half — the engine reports a
 * dead-looking bridge through [invalidate], and the very next search
 * re-fetches the remote config instead of hammering an address that will
 * never answer again. Republishing the config file heals installed apps
 * on their next retry, with no process restart and no app update.
 *
 * The remote file's shape is `{"baseUrl": "https://host"}`.
 */
class SearchBackendConfig(
    baseClient: OkHttpClient,
    private val json: Json,
    private val remoteUrl: String = REMOTE_CONFIG_URL,
    private val defaultBaseUrl: String = DEFAULT_BASE_URL,
    /** Injectable clock so tests can age a fetch without sleeping. */
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Serializable
    private data class BackendConfigDto(
        val baseUrl: String? = null,
    )

    /** Config fetches are tiny and must never stall a search behind a slow one. */
    private val fetchClient =
        baseClient
            .newBuilder()
            .connectTimeout(CONFIG_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(CONFIG_TIMEOUT_S, TimeUnit.SECONDS)
            .callTimeout(CONFIG_TIMEOUT_S + 2, TimeUnit.SECONDS)
            .build()

    private val mutex = Mutex()

    /** The override last fetched from the remote config; null until a fetch succeeds. */
    @Volatile
    private var remoteOverride: String? = null

    /** [clock] time of the last fetch attempt (success OR failure); 0 means "never". */
    private val lastFetchedAtMs = AtomicLong(0L)

    /**
     * The backend base URL in force right now: the remote override when the
     * last fetch succeeded, otherwise the built-in default. The first call
     * performs (and absorbs) the fetch; later calls are lock-free while the
     * fetch is fresh. A fetch goes stale with age or after [invalidate] —
     * every stale call re-fetches exactly once under the lock, so a
     * republished config file heals the next search, not the next process.
     */
    suspend fun baseUrl(): String {
        if (isStale()) {
            mutex.withLock {
                if (isStale()) {
                    remoteOverride = fetchRemoteOverride()
                    lastFetchedAtMs.set(clock())
                }
            }
        }
        return remoteOverride ?: defaultBaseUrl
    }

    /**
     * Reports the bridge as (probably) dead — the engine calls this when a
     * search fails in a way a live bridge would not (transport error,
     * timeout, HTTP error status: the recycled workspace answered 410 Gone,
     * a dead tunnel stops resolving DNS). The override is marked stale, so
     * the next [baseUrl] re-fetches the remote config. Safe to call from
     * any thread; costs at most one extra config GET per search.
     */
    fun invalidate() {
        lastFetchedAtMs.set(0L)
    }

    private fun isStale(): Boolean {
        val at = lastFetchedAtMs.get()
        return at == 0L || clock() - at > REFRESH_TTL_MS
    }

    /** One best-effort fetch, never throwing: a config problem must never break search. */
    private fun fetchRemoteOverride(): String? =
        try {
            fetchClient
                .newCall(Request.Builder().url(remoteUrl).build())
                .execute()
                .use { response ->
                    if (!response.isSuccessful) return null
                    val body = response.body?.string().orEmpty()
                    json
                        .decodeFromString(BackendConfigDto.serializer(), body)
                        .baseUrl
                        ?.trim()
                        ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                }
        } catch (_: Exception) {
            null
        }

    companion object {
        /**
         * The sandbox's public bridge address. If this ever goes stale, the
         * remote config file is the fix that reaches installed apps at once
         * — and since v1.2.1 they pick the fix up on their next retry.
         */
        const val DEFAULT_BASE_URL = "https://ws-a49020ba-862e-49d0-bdae-c0a4764d68ea.space-z.ai"

        /** The remote override, served from the app repo's main branch. */
        const val REMOTE_CONFIG_URL =
            "https://raw.githubusercontent.com/alamsamir7666-ux/Cloud-Wallpaper/main/search-backend.json"

        /**
         * How long a fetched override stays fresh when nothing invalidates
         * it: long enough that ordinary searching never pays for a config
         * GET, short enough that a republished file reaches an app that
         * has been open since breakfast.
         */
        private const val REFRESH_TTL_MS = 10L * 60L * 1000L

        private const val CONFIG_TIMEOUT_S = 10L
    }
}
