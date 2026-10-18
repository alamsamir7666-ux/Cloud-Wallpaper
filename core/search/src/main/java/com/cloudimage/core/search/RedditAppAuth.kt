package com.cloudimage.core.search

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Reddit's application-only OAuth for INSTALLED apps — the sanctioned,
 * no-login identity that lifts the Reddit lane out of the anonymous IP
 * budget (v1.2.25).
 *
 * The problem this solves: the unauthenticated JSON API throttles by IP
 * (~10 requests/minute) and answers shared mobile-carrier IPs (CGNAT, one
 * public address for thousands of subscribers) with a permanently drained
 * budget — the lane died silently on exactly the networks the app rides.
 * An installed-app client instead identifies itself with a client_id the
 * developer registers once at reddit.com/prefs/apps plus a per-installation
 * `device_id` (random, persisted, tied to no user account): the grant
 * `https://oauth.reddit.com/grants/installed_client` exchanges those for a
 * bearer token whose traffic rides the OAuth budget (~100 requests/minute
 * per client_id) — and because the credential names the app and the
 * device, not the IP, it survives CGNAT by construction. No user ever
 * logs in; nothing about a Reddit account is known.
 *
 * The client_id is not a secret — installed apps ship it in the binary —
 * so it is remote-configurable ([SearchBackendConfig.redditClientId],
 * with a baked default) and can be rotated without an app update. The
 * device_id is generated once ([RedditDeviceIdStore]) and stored in the
 * app's private preferences.
 *
 * Token lifecycle: one POST to `/api/v1/access_token` (Basic auth of
 * `client_id:` — installed apps have no secret), a one-hour `expires_in`,
 * cached in memory and refreshed ~5 minutes early so a page never races
 * the expiry. Failures here are the caller's signal to fall back to the
 * anonymous lane — never a crash, never a retry storm.
 */
class RedditAppAuth(
    baseClient: OkHttpClient,
    private val json: Json,
    private val deviceIdStore: RedditDeviceIdStore,
    /** Tests point this at MockWebServer; production leaves it null. */
    private val overrideTokenBaseUrl: String? = null,
    /** Injectable clock so tests can age a token without sleeping. */
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Token calls are their own small budget on Reddit's side and must
     * never inherit the listing lane's generous timeouts.
     */
    private val client =
        baseClient
            .newBuilder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
            .build()

    /** One refresh at a time; searches otherwise stampede the endpoint. */
    private val mutex = Mutex()

    @Volatile
    private var cached: CachedToken? = null

    private data class CachedToken(
        val token: String,
        val expiresAtMs: Long,
    )

    /**
     * The bearer token for [clientId], or null if it could not be
     * obtained (network error, non-200, malformed body). A cached
     * unexpired token is returned without any I/O; a refresh runs under
     * the mutex exactly once no matter how many searches arrive together.
     */
    suspend fun bearerToken(clientId: String): String? {
        val token = cached
        if (token != null && clock() < token.expiresAtMs) return token.token
        return mutex.withLock {
            val again = cached
            if (again != null && clock() < again.expiresAtMs) return@withLock again.token
            fetchToken(clientId)?.also { cached = it }?.token
        }
    }

    /**
     * Drops the cached token — the engine calls this when the API answers
     * 401 (expired early / revoked), so the next search mints a fresh one
     * instead of riding a token Reddit has already refused.
     */
    fun invalidate() {
        cached = null
    }

    private suspend fun fetchToken(clientId: String): CachedToken? =
        withContext(Dispatchers.IO) {
            try {
                val deviceId = deviceIdStore.getOrCreate()
                val body =
                    FormBody
                        .Builder()
                        .add("grant_type", GRANT_TYPE)
                        .add("device_id", deviceId)
                        .build()
                val request =
                    Request
                        .Builder()
                        .url("${tokenBaseUrl()}/api/v1/access_token")
                        // Installed apps authenticate with the client_id and an
                        // EMPTY secret — Basic "client_id:" is Reddit's own shape.
                        .header("Authorization", Credentials.basic(clientId, ""))
                        .header("User-Agent", RedditSearchEngine.USER_AGENT)
                        .post(body)
                        .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val dto =
                        try {
                            json.decodeFromString(
                                TokenDto.serializer(),
                                response.body?.string().orEmpty(),
                            )
                        } catch (_: SerializationException) {
                            return@use null
                        }
                    if (dto.accessToken.isBlank()) return@use null
                    val ttlSeconds = dto.expiresIn.coerceAtLeast(MIN_TTL_S)
                    CachedToken(
                        token = dto.accessToken,
                        // The refresh margin must never push a short-lived
                        // token's expiry into the past: a TTL beneath the
                        // margin degrades to the floor, not to "instantly
                        // stale" (which would re-mint on every search).
                        expiresAtMs =
                            clock() +
                                maxOf(
                                    ttlSeconds * 1000L - EXPIRY_MARGIN_MS,
                                    MIN_EFFECTIVE_TTL_MS,
                                ),
                    )
                }
            } catch (_: Exception) {
                null
            }
        }

    private fun tokenBaseUrl(): String = (overrideTokenBaseUrl ?: DEFAULT_TOKEN_BASE_URL).trimEnd('/')

    @Serializable
    private data class TokenDto(
        @SerialName("access_token") val accessToken: String = "",
        @SerialName("expires_in") val expiresIn: Long = DEFAULT_EXPIRES_IN_S,
    )

    companion object {
        /**
         * The token endpoint deliberately lives on www.reddit.com — the
         * OAuth host serves the API, the plain host mints the tokens.
         */
        private const val DEFAULT_TOKEN_BASE_URL = "https://www.reddit.com"

        /** Reddit's grant string for installed (no-user) apps. */
        private const val GRANT_TYPE = "https://oauth.reddit.com/grants/installed_client"

        private const val DEFAULT_EXPIRES_IN_S = 3600L

        /** Never trust a shorter TTL than this — a token must outlive a page fetch. */
        private const val MIN_TTL_S = 60L

        /** Refresh this far ahead of expiry so no search rides a dying token. */
        private const val EXPIRY_MARGIN_MS = 300_000L

        /** The floor for a token's cached lifetime, margin notwithstanding. */
        private const val MIN_EFFECTIVE_TTL_MS = 30_000L

        private const val CONNECT_TIMEOUT_S = 10L
        private const val READ_TIMEOUT_S = 15L
        private const val CALL_TIMEOUT_S = 20L
    }
}

/**
 * Supplies the per-installation device identity for the installed-client
 * grant: create once, return the same string forever after. Production
 * binds to [sharedPrefs]; tests bind to a fixed string.
 */
fun interface RedditDeviceIdStore {
    suspend fun getOrCreate(): String

    companion object {
        private const val PREFS_NAME = "reddit_oauth"
        private const val KEY_DEVICE_ID = "device_id"
        private const val DEVICE_ID_LENGTH = 22

        /** The production store: app-private preferences, created lazily. */
        fun sharedPrefs(context: Context): RedditDeviceIdStore =
            RedditDeviceIdStore {
                withContext(Dispatchers.IO) {
                    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val existing = prefs.getString(KEY_DEVICE_ID, null)
                    if (existing != null) {
                        existing
                    } else {
                        val generated = generateDeviceId()
                        prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
                        generated
                    }
                }
            }

        /**
         * A random alphanumeric device id — Reddit asks for 20–30
         * characters of opaque string; it identifies THIS installation,
         * not a user, and is regenerated only by a reinstall.
         */
        private fun generateDeviceId(): String {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            val random = SecureRandom()
            return buildString {
                repeat(DEVICE_ID_LENGTH) {
                    append(alphabet[random.nextInt(alphabet.length)])
                }
            }
        }
    }
}
