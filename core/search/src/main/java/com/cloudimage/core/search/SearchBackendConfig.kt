package com.cloudimage.core.search

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

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
 * The remote file's shape is `{"baseUrl": "https://host"}`.
 */
class SearchBackendConfig(
    baseClient: OkHttpClient,
    private val json: Json,
    private val remoteUrl: String = REMOTE_CONFIG_URL,
    private val defaultBaseUrl: String = DEFAULT_BASE_URL,
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

    /** The override fetched from the remote config, once per process. */
    private var remoteOverride: String? = null

    /** Whether the one-per-process fetch has run (success or not). */
    private var fetched = false

    /**
     * The backend base URL in force right now: the remote override when it
     * was fetched successfully, otherwise the built-in default. The first
     * call performs (and absorbs) the fetch; later calls are lock-free.
     */
    suspend fun baseUrl(): String {
        if (!fetched) {
            mutex.withLock {
                if (!fetched) {
                    remoteOverride = fetchRemoteOverride()
                    fetched = true
                }
            }
        }
        return remoteOverride ?: defaultBaseUrl
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
         * remote config file is the fix that reaches installed apps at once.
         */
        const val DEFAULT_BASE_URL = "https://ws-a49020ba-862e-49d0-bdae-c0a4764d68ea.space-z.ai"

        /** The remote override, served from the app repo's main branch. */
        const val REMOTE_CONFIG_URL =
            "https://raw.githubusercontent.com/alamsamir7666-ux/Cloud-Wallpaper/main/search-backend.json"

        private const val CONFIG_TIMEOUT_S = 10L
    }
}
