package com.cloudimage.core.data.rotation

import com.cloudimage.core.data.repository.ApplyError
import com.cloudimage.core.data.repository.ApplyResult
import com.cloudimage.core.data.repository.ApplyTarget
import com.cloudimage.core.data.repository.FavoritesRepository
import com.cloudimage.core.data.repository.HistoryRepository
import com.cloudimage.core.data.repository.WallpaperApplier
import com.cloudimage.core.datastore.UserPreferencesRepository
import com.cloudimage.core.model.Favorite
import com.cloudimage.core.model.HistoryAction
import com.cloudimage.core.model.RotationTarget
import com.cloudimage.core.model.Wallpaper
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of one rotation attempt. */
sealed interface RotationResult {
    /** A new wallpaper was applied to the target screen(s). */
    data class Success(
        val wallpaper: Wallpaper,
    ) : RotationResult

    /** The user has no saved wallpapers; nothing was attempted. */
    data object NoWallpapers : RotationResult

    /** A wallpaper was picked but applying it failed. */
    data class ApplyFailed(
        val error: ApplyError,
    ) : RotationResult
}

/**
 * Applies the next saved wallpaper: one step of the auto-rotate carousel.
 *
 * The pick is a stable round-robin over the favorites (ordered by save date)
 * rather than a random shuffle, so every saved wallpaper gets its turn and
 * the user never sees the same one twice in a row (unless it is the only
 * one saved).
 */
interface WallpaperRotator {
    /** Picks the next favorite and applies it to [target]. */
    suspend fun rotateOnce(target: RotationTarget): RotationResult
}

/**
 * Production [WallpaperRotator]. The rotation cursor ("providerId/id" of the
 * last pick) advances BEFORE the apply is attempted, so a favorite whose
 * download is permanently broken cannot stall the carousel: the next run —
 * or a worker retry — simply picks the one after it.
 */
@Singleton
class FavoriteWallpaperRotator
    @Inject
    constructor(
        private val favoritesRepository: FavoritesRepository,
        private val historyRepository: HistoryRepository,
        private val applier: WallpaperApplier,
        private val userPreferencesRepository: UserPreferencesRepository,
    ) : WallpaperRotator {
        override suspend fun rotateOnce(target: RotationTarget): RotationResult {
            val favorites = favoritesRepository.observeFavorites().first()
            val lastKey = userPreferencesRepository.lastRotationKey.first()
            val next = pickNextRotation(favorites, lastKey) ?: return RotationResult.NoWallpapers

            userPreferencesRepository.setLastRotationKey(next.rotationKey())

            return when (val result = applier.apply(next, target.toApplyTarget())) {
                is ApplyResult.Success -> {
                    historyRepository.record(next, HistoryAction.APPLIED)
                    RotationResult.Success(next)
                }

                is ApplyResult.Failure -> RotationResult.ApplyFailed(result.error)
            }
        }
    }

/** Stable identity of a wallpaper within the rotation cursor. */
fun Wallpaper.rotationKey(): String = "$providerId/$id"

/**
 * The pure pick: the favorite after [lastKey] in save-date order, wrapping
 * around; a cursor pointing at a removed (un-favorited) wallpaper restarts
 * from the oldest save. Returns null only for an empty collection.
 */
internal fun pickNextRotation(
    favorites: List<Favorite>,
    lastKey: String?,
): Wallpaper? {
    if (favorites.isEmpty()) return null
    val ordered = favorites.sortedBy { it.addedAtMillis }
    val position = ordered.indexOfFirst { it.wallpaper.rotationKey() == lastKey }
    val next = if (position < 0) 0 else (position + 1) % ordered.size
    return ordered[next].wallpaper
}

/** Maps the model-layer target onto the apply pipeline's target. */
internal fun RotationTarget.toApplyTarget(): ApplyTarget =
    when (this) {
        RotationTarget.HOME -> ApplyTarget.HOME
        RotationTarget.LOCK -> ApplyTarget.LOCK
        RotationTarget.BOTH -> ApplyTarget.BOTH
    }
