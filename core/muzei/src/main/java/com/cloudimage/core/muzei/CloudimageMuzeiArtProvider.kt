package com.cloudimage.core.muzei

import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.RemoteActionCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.apps.muzei.api.provider.Artwork
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider

/**
 * Cloudimage's Muzei source. Muzei discovers this provider through its
 * manifest action and calls [onLoadRequested] whenever it needs artwork —
 * on first selection, after the user cycles past the last artwork of a
 * batch, and after an artwork is found invalid.
 *
 * The provider itself stays a thin shell: selection, filtering and the
 * failure taxonomy live in [MuzeiArtworkSelector] and run inside
 * [MuzeiArtworkWorker].
 */
class CloudimageMuzeiArtProvider : MuzeiArtProvider() {
    override fun onLoadRequested(initial: Boolean) {
        val context = context ?: return
        // KEEP: a load may already be queued while Muzei re-requests (a
        // retry with backoff can still be pending); stampeding the
        // selector would only burn through the rotation cursor.
        WorkManager.getInstance(context).enqueueUniqueWork(
            MuzeiArtworkWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<MuzeiArtworkWorker>().build(),
        )
    }

    override fun getCommandActions(artwork: Artwork): List<RemoteActionCompat> {
        val context = context ?: return super.getCommandActions(artwork)
        val launchIntent =
            context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: return super.getCommandActions(artwork)
        val title = context.getString(R.string.muzei_command_open)
        val pendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return listOf(
            RemoteActionCompat(
                // The launch icon muzei-api ships exactly for source actions.
                IconCompat.createWithResource(
                    context,
                    com.google.android.apps.muzei.api.R.drawable.muzei_launch_command,
                ),
                title,
                title,
                pendingIntent,
            ),
        )
    }
}
