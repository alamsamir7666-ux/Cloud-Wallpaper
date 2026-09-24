package com.cloudimage.core.model

/**
 * Which screen(s) auto-rotation applies a wallpaper to.
 *
 * Mirrors the apply pipeline's target but lives in the model layer so
 * settings can reference it without depending on core:data.
 */
enum class RotationTarget {
    HOME,
    LOCK,
    BOTH,
}

/**
 * User-facing knobs of the wallpaper auto-rotator: whether it runs, how
 * often, which screens it touches, and whether metered networks are spent
 * on full-resolution downloads.
 */
data class RotationSettings(
    val enabled: Boolean = false,
    val intervalMinutes: Int = DEFAULT_INTERVAL_MINUTES,
    val wifiOnly: Boolean = false,
    val target: RotationTarget = RotationTarget.HOME,
) {
    companion object {
        /**
         * The intervals the settings screen offers, in minutes. WorkManager
         * cannot schedule periodic work faster than every 15 minutes, and the
         * fastest offered cadence deliberately stays above that floor.
         */
        val INTERVAL_CHOICES_MINUTES = listOf(30, 60, 180, 360, 720, 1440)

        /** Three hours: four fresh wallpapers over a waking day, gentle on data. */
        const val DEFAULT_INTERVAL_MINUTES = 180
    }
}
