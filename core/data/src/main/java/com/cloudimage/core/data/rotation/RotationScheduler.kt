package com.cloudimage.core.data.rotation

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.cloudimage.core.model.RotationSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the system's periodic rotation work in lockstep with the stored
 * settings. Features and the bootstrapper drive this seam; WorkManager stays
 * behind it.
 */
interface RotationScheduler {
    /**
     * Schedules, updates or cancels the periodic rotation so it matches
     * [settings]. Idempotent — calling it repeatedly with unchanged
     * settings leaves the running cycle alone.
     */
    fun sync(settings: RotationSettings)
}

/**
 * WorkManager-backed [RotationScheduler]. A thin, untested seam over the
 * framework (like [com.cloudimage.core.data.platform.WallpaperManagerSetter]):
 * the testable decisions — constraints and the offered cadences — live in
 * pure functions below.
 */
@Singleton
class WorkManagerRotationScheduler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : RotationScheduler {
        override fun sync(settings: RotationSettings) {
            val manager = WorkManager.getInstance(context)
            if (!settings.enabled) {
                manager.cancelUniqueWork(WallpaperRotateWorker.UNIQUE_WORK_NAME)
                return
            }
            val request =
                PeriodicWorkRequestBuilder<WallpaperRotateWorker>(
                    settings.intervalMinutes
                        .coerceAtLeast(WORK_MANAGER_MIN_INTERVAL_MINUTES)
                        .toLong(),
                    TimeUnit.MINUTES,
                ).setConstraints(rotationConstraints(settings.wifiOnly))
                    .build()
            // UPDATE, not REPLACE: process restarts with unchanged settings
            // must not reset the running periodic cycle.
            manager.enqueueUniquePeriodicWork(
                WallpaperRotateWorker.UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }

/**
 * When rotation may run: never on a low battery, and only on a network the
 * user is willing to spend (unmetered when Wi-Fi only is on, any connection
 * otherwise — the apply pipeline downloads full-resolution images).
 */
internal fun rotationConstraints(wifiOnly: Boolean): Constraints =
    Constraints
        .Builder()
        .setRequiresBatteryNotLow(true)
        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .build()

/** WorkManager refuses periodic work faster than every 15 minutes. */
internal const val WORK_MANAGER_MIN_INTERVAL_MINUTES = 15
