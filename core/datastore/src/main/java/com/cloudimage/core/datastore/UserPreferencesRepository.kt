package com.cloudimage.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.cloudimage.core.model.RotationSettings
import com.cloudimage.core.model.RotationTarget
import com.cloudimage.core.model.UserPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private object PreferencesKeys {
    val SFW_ONLY = booleanPreferencesKey("sfw_only")
    val DYNAMIC_COLORS = booleanPreferencesKey("dynamic_colors_enabled")
    val GRID_COLUMNS = intPreferencesKey("grid_columns")
    val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
    val PROVIDER_KEY_IDS = stringSetPreferencesKey("provider_key_ids")

    // Wallpaper auto-rotation (v1.0.3).
    val ROTATION_ENABLED = booleanPreferencesKey("rotation_enabled")
    val ROTATION_INTERVAL = intPreferencesKey("rotation_interval_minutes")
    val ROTATION_WIFI_ONLY = booleanPreferencesKey("rotation_wifi_only")
    val ROTATION_TARGET = stringPreferencesKey("rotation_target")
    val LAST_ROTATION_KEY = stringPreferencesKey("last_rotation_key")

    // Muzei source state (v1.0.4).
    val MUZEI_CURSOR = intPreferencesKey("muzei_cursor")
    val MUZEI_FEED_PAGE = intPreferencesKey("muzei_feed_page")
}

private const val PREFERENCES_FILE = "user_preferences"
private const val PROVIDER_KEY_PREFIX = "provider_key."

/**
 * Reads the rotation knobs out of a preferences snapshot. Unknown or stale
 * values (a cadence this build no longer offers, a target name from a
 * settings backup) degrade to defaults instead of crashing or blocking.
 */
private fun Preferences.toRotationSettings(): RotationSettings {
    val interval =
        this[PreferencesKeys.ROTATION_INTERVAL]
            ?.takeIf { it in RotationSettings.INTERVAL_CHOICES_MINUTES }
            ?: RotationSettings.DEFAULT_INTERVAL_MINUTES
    val target =
        this[PreferencesKeys.ROTATION_TARGET]
            ?.let { stored -> runCatching { RotationTarget.valueOf(stored) }.getOrNull() }
            ?: RotationTarget.HOME
    return RotationSettings(
        enabled = this[PreferencesKeys.ROTATION_ENABLED] ?: false,
        intervalMinutes = interval,
        wifiOnly = this[PreferencesKeys.ROTATION_WIFI_ONLY] ?: false,
        target = target,
    )
}

/**
 * Reads and writes user settings via Preferences DataStore.
 *
 * DataStore (the modern SharedPreferences replacement) writes asynchronously to
 * disk and exposes a [Flow], so the UI always reflects the latest persisted
 * state. A corrupted file degrades to defaults instead of crashing the app.
 */
@Singleton
class UserPreferencesRepository
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) {
        /** Emits the current settings on subscribe and after every change. */
        val preferences: Flow<UserPreferences> =
            dataStore.data
                .catch { exception ->
                    if (exception is IOException) {
                        emit(emptyPreferences())
                    } else {
                        throw exception
                    }
                }.map { prefs ->
                    UserPreferences(
                        sfwOnly = prefs[PreferencesKeys.SFW_ONLY] ?: true,
                        dynamicColorsEnabled = prefs[PreferencesKeys.DYNAMIC_COLORS] ?: true,
                        gridColumns = prefs[PreferencesKeys.GRID_COLUMNS] ?: 2,
                        onboardingCompleted = prefs[PreferencesKeys.ONBOARDING_COMPLETED] ?: false,
                        rotation = prefs.toRotationSettings(),
                    )
                }

        suspend fun setSfwOnly(enabled: Boolean) {
            dataStore.edit { it[PreferencesKeys.SFW_ONLY] = enabled }
        }

        suspend fun setDynamicColorsEnabled(enabled: Boolean) {
            dataStore.edit { it[PreferencesKeys.DYNAMIC_COLORS] = enabled }
        }

        suspend fun setGridColumns(columns: Int) {
            dataStore.edit { it[PreferencesKeys.GRID_COLUMNS] = columns.coerceIn(minimumValue = 1, maximumValue = 4) }
        }

        /** Marks the first-run welcome flow as finished. Never un-finished. */
        suspend fun setOnboardingCompleted() {
            dataStore.edit { it[PreferencesKeys.ONBOARDING_COMPLETED] = true }
        }

        // ---- Wallpaper auto-rotation (v1.0.3) ----

        suspend fun setRotationEnabled(enabled: Boolean) {
            dataStore.edit { it[PreferencesKeys.ROTATION_ENABLED] = enabled }
        }

        /** Only the offered cadences are stored; anything else falls back to the default. */
        suspend fun setRotationInterval(minutes: Int) {
            dataStore.edit {
                it[PreferencesKeys.ROTATION_INTERVAL] =
                    minutes.takeIf { candidate -> candidate in RotationSettings.INTERVAL_CHOICES_MINUTES }
                        ?: RotationSettings.DEFAULT_INTERVAL_MINUTES
            }
        }

        suspend fun setRotationWifiOnly(wifiOnly: Boolean) {
            dataStore.edit { it[PreferencesKeys.ROTATION_WIFI_ONLY] = wifiOnly }
        }

        suspend fun setRotationTarget(target: RotationTarget) {
            dataStore.edit { it[PreferencesKeys.ROTATION_TARGET] = target.name }
        }

        /** Where the rotator left off: "providerId/wallpaperId" of the last pick. */
        val lastRotationKey: Flow<String?> =
            dataStore.data
                .catch { exception ->
                    if (exception is IOException) {
                        emit(emptyPreferences())
                    } else {
                        throw exception
                    }
                }.map { it[PreferencesKeys.LAST_ROTATION_KEY] }

        suspend fun setLastRotationKey(key: String) {
            dataStore.edit { it[PreferencesKeys.LAST_ROTATION_KEY] = key }
        }

        // ---- Muzei source (v1.0.4) ----

        /**
         * Where the Muzei carousel left off: index of the favorite the next
         * batch starts from (taken modulo the favorites count on read, so
         * un-favoriting can never leave a dangling cursor).
         */
        val muzeiCursor: Flow<Int> =
            dataStore.data
                .catch { exception ->
                    if (exception is IOException) {
                        emit(emptyPreferences())
                    } else {
                        throw exception
                    }
                }.map { it[PreferencesKeys.MUZEI_CURSOR] ?: 0 }

        suspend fun setMuzeiCursor(cursor: Int) {
            dataStore.edit { it[PreferencesKeys.MUZEI_CURSOR] = cursor }
        }

        /**
         * The feed page the Muzei source serves next, for installs with no
         * saved wallpapers yet. Wraps back to 1 when a source runs dry.
         */
        val muzeiFeedPage: Flow<Int> =
            dataStore.data
                .catch { exception ->
                    if (exception is IOException) {
                        emit(emptyPreferences())
                    } else {
                        throw exception
                    }
                }.map { it[PreferencesKeys.MUZEI_FEED_PAGE] ?: 1 }

        suspend fun setMuzeiFeedPage(page: Int) {
            dataStore.edit { it[PreferencesKeys.MUZEI_FEED_PAGE] = page.coerceAtLeast(1) }
        }

        /** The stored API key of every provider, keyed by provider id. */
        val providerApiKeys: Flow<Map<String, String>> =
            dataStore.data
                .catch { exception ->
                    if (exception is IOException) {
                        emit(emptyPreferences())
                    } else {
                        throw exception
                    }
                }.map { prefs ->
                    val ids = prefs[PreferencesKeys.PROVIDER_KEY_IDS].orEmpty()
                    ids
                        .mapNotNull { id ->
                            prefs[stringPreferencesKey(PROVIDER_KEY_PREFIX + id)]?.let { id to it }
                        }.toMap()
                }

        /** Stores (or replaces, with a blank key clears) the key of one provider. */
        suspend fun setProviderApiKey(
            providerId: String,
            apiKey: String,
        ) {
            dataStore.edit { prefs ->
                val ids = prefs[PreferencesKeys.PROVIDER_KEY_IDS].orEmpty()
                if (apiKey.isBlank()) {
                    prefs.remove(stringPreferencesKey(PROVIDER_KEY_PREFIX + providerId))
                    prefs[PreferencesKeys.PROVIDER_KEY_IDS] = ids - providerId
                } else {
                    prefs[stringPreferencesKey(PROVIDER_KEY_PREFIX + providerId)] = apiKey
                    prefs[PreferencesKeys.PROVIDER_KEY_IDS] = ids + providerId
                }
            }
        }
    }

@Module
@InstallIn(SingletonComponent::class)
internal object DatastoreModule {
    @Provides
    @Singleton
    fun provideDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile(PREFERENCES_FILE) },
        )
}
