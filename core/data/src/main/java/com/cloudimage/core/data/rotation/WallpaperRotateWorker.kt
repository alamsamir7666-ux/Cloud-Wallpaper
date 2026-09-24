package com.cloudimage.core.data.rotation

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.cloudimage.core.datastore.UserPreferencesRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * The periodic auto-rotate step: applies the next saved wallpaper. A thin
 * shell over [WallpaperRotator] — every meaningful decision is tested there;
 * this class only maps results onto WorkManager outcomes.
 */
@HiltWorker
class WallpaperRotateWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        private val rotator: WallpaperRotator,
        private val userPreferencesRepository: UserPreferencesRepository,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            // Read the knobs at run time: the schedule (interval, constraints)
            // lives in the work spec, but the target screen may have changed
            // since the work was enqueued.
            val target =
                userPreferencesRepository.preferences
                    .first()
                    .rotation.target
            return when (rotator.rotateOnce(target)) {
                is RotationResult.Success -> Result.success()

                // Nothing saved: the run "succeeded" — retrying immediately
                // would not change anything. The next period picks up again
                // once wallpapers have been saved.
                RotationResult.NoWallpapers -> Result.success()

                // The pick may fail transiently (offline mid-download). The
                // rotator already advanced the cursor, so a retry moves on
                // to the next saved wallpaper instead of hammering one URL.
                is RotationResult.ApplyFailed -> Result.retry()
            }
        }

        companion object {
            const val UNIQUE_WORK_NAME = "wallpaper-rotation"
        }
    }
