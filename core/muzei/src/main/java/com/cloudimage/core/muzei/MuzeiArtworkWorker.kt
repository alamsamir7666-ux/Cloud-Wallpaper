package com.cloudimage.core.muzei

import android.content.Context
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.apps.muzei.api.provider.Artwork
import com.google.android.apps.muzei.api.provider.ProviderContract
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Loads the next artwork batch for the Muzei source: a thin shell over
 * [MuzeiArtworkSelector] — every meaningful decision is tested there;
 * this class only maps outcomes onto WorkManager results and pushes the
 * batch into the provider.
 */
@HiltWorker
class MuzeiArtworkWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        private val selector: MuzeiArtworkSelector,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result =
            when (val batch = selector.nextBatch()) {
                is MuzeiBatch.Artworks -> {
                    val client =
                        ProviderContract.getProviderClient(
                            applicationContext,
                            applicationContext.packageName + AUTHORITY_SUFFIX,
                        )
                    client.setArtwork(batch.wallpapers.map { it.toMuzeiArtworkSpec().toArtwork() })
                    Result.success()
                }

                // Nothing to serve (no favorites, broken sources): retrying
                // would not change anything, and Muzei keeps its current
                // artwork until the next load request.
                MuzeiBatch.Empty -> Result.success()

                // Transient (offline, HTTP error): WorkManager's backoff
                // retries once connectivity returns.
                is MuzeiBatch.Retryable -> Result.retry()
            }

        companion object {
            /** The Muzei authority, appended to the application id. */
            const val AUTHORITY_SUFFIX = ".muzei"

            const val UNIQUE_WORK_NAME = "muzei-artwork-load"
        }
    }

/** Builds the Muzei artwork record for one wallpaper — Android glue over the tested spec. */
internal fun MuzeiArtworkSpec.toArtwork(): Artwork {
    val builder =
        Artwork.Builder()
            .token(token)
            .title(title)
            .byline(byline)
            .attribution(attribution)
            .persistentUri(persistentUri.toUri())
    webUri?.let { builder.webUri(it.toUri()) }
    return builder.build()
}
