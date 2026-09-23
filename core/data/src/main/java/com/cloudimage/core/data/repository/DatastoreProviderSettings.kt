package com.cloudimage.core.data.repository

import com.cloudimage.core.datastore.UserPreferencesRepository
import com.cloudimage.provider.api.ProviderSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Host-side [ProviderSettings] backed by the DataStore key map.
 *
 * The store is suspend-read, but the provider contract needs a cheap
 * synchronous lookup — so the latest map is kept in a volatile snapshot
 * that a collector refreshes on every change. Key edits take effect for
 * already-loaded providers on their next request; no reload needed.
 */
@Singleton
class DatastoreProviderSettings
    @Inject
    constructor(
        userPreferencesRepository: UserPreferencesRepository,
        appScope: CoroutineScope,
    ) : ProviderSettings {
        @Volatile
        private var keys: Map<String, String> = emptyMap()

        init {
            appScope.launch {
                userPreferencesRepository.providerApiKeys.collect { keys = it }
            }
        }

        override fun apiKey(providerId: String): String? = keys[providerId]
    }
