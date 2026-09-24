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
}

private const val PREFERENCES_FILE = "user_preferences"
private const val PROVIDER_KEY_PREFIX = "provider_key."

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
                }
                .map { prefs ->
                    UserPreferences(
                        sfwOnly = prefs[PreferencesKeys.SFW_ONLY] ?: true,
                        dynamicColorsEnabled = prefs[PreferencesKeys.DYNAMIC_COLORS] ?: true,
                        gridColumns = prefs[PreferencesKeys.GRID_COLUMNS] ?: 2,
                        onboardingCompleted = prefs[PreferencesKeys.ONBOARDING_COMPLETED] ?: false,
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

        /** The stored API key of every provider, keyed by provider id. */
        val providerApiKeys: Flow<Map<String, String>> =
            dataStore.data
                .catch { exception ->
                    if (exception is IOException) {
                        emit(emptyPreferences())
                    } else {
                        throw exception
                    }
                }
                .map { prefs ->
                    val ids = prefs[PreferencesKeys.PROVIDER_KEY_IDS].orEmpty()
                    ids.mapNotNull { id ->
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
