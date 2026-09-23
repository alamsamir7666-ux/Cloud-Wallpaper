package com.cloudimage.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
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
}

private const val PREFERENCES_FILE = "user_preferences"

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
