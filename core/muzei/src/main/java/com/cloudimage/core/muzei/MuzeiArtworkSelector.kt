package com.cloudimage.core.muzei

import com.cloudimage.core.data.repository.FavoritesRepository
import com.cloudimage.core.data.repository.WallpaperSources
import com.cloudimage.core.datastore.UserPreferencesRepository
import com.cloudimage.core.model.ContentRating
import com.cloudimage.core.model.Wallpaper
import com.cloudimage.core.model.WallpaperQuery
import com.cloudimage.core.network.NetworkError
import com.cloudimage.core.network.NetworkResult
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** What one artwork batch request produced for Muzei. */
sealed interface MuzeiBatch {
    /** Wallpapers to hand to Muzei, in play order. */
    data class Artworks(val wallpapers: List<Wallpaper>) : MuzeiBatch

    /** Nothing to serve right now; Muzei keeps whatever it already shows. */
    data object Empty : MuzeiBatch

    /** A transient failure (offline, HTTP error) — worth retrying with backoff. */
    data class Retryable(val reason: String) : MuzeiBatch
}

/** Picks the next artwork batch for the Muzei source. */
interface MuzeiArtworkSelector {
    suspend fun nextBatch(): MuzeiBatch
}

/**
 * Production [MuzeiArtworkSelector].
 *
 * The carousel is the saved wallpapers (save-date order, filtered by the
 * SFW-only setting), served as one rotated list per load: Muzei walks the
 * batch with its own "next artwork", and when the batch runs out it calls
 * back — at which point the next rotation starts one favorite further
 * along, so every save gets its turn and the same one never leads twice in
 * a row (unless it is the only one saved).
 *
 * With nothing saved, a fresh install still gets art: the default feed page
 * serves instead, advancing one page per load and wrapping back to the
 * first when the source runs dry.
 */
@Singleton
class FavoriteMuzeiArtworkSelector
    @Inject
    constructor(
        private val favoritesRepository: FavoritesRepository,
        private val wallpaperSources: WallpaperSources,
        private val userPreferencesRepository: UserPreferencesRepository,
    ) : MuzeiArtworkSelector {
        override suspend fun nextBatch(): MuzeiBatch {
            val prefs = userPreferencesRepository.preferences.first()
            val allowed = allowedRatings(sfwOnly = prefs.sfwOnly)
            val favorites =
                favoritesRepository.observeFavorites().first()
                    .filter { it.wallpaper.contentRating in allowed }
                    .sortedBy { it.addedAtMillis }

            if (favorites.isNotEmpty()) {
                val window = favorites.takeLast(MAX_ARTWORKS).map { it.wallpaper }
                val cursor = userPreferencesRepository.muzeiCursor.first().mod(window.size)
                // Advance BEFORE handing the batch out, mirroring the
                // auto-rotator: a wallpaper whose download is permanently
                // broken cannot stall the carousel — the next load simply
                // starts one further along.
                userPreferencesRepository.setMuzeiCursor((cursor + 1).mod(window.size))
                return MuzeiBatch.Artworks(window.rotateBy(cursor))
            }

            return feedBatch(allowed = allowed)
        }

        /**
         * No favorites yet: serve the default feed, one page per load. The
         * page cursor persists across loads and wraps to the first page
         * when the feed runs out.
         */
        private suspend fun feedBatch(allowed: Set<ContentRating>): MuzeiBatch {
            val page = userPreferencesRepository.muzeiFeedPage.first().coerceAtLeast(FIRST_PAGE)
            return when (val result = wallpaperSources.search(WallpaperQuery(contentRatings = allowed), page)) {
                is NetworkResult.Success -> {
                    val wallpapers = result.value.wallpapers
                    if (wallpapers.isEmpty()) {
                        userPreferencesRepository.setMuzeiFeedPage(FIRST_PAGE)
                        MuzeiBatch.Empty
                    } else {
                        userPreferencesRepository.setMuzeiFeedPage(result.value.nextPage ?: FIRST_PAGE)
                        MuzeiBatch.Artworks(wallpapers.take(MAX_ARTWORKS))
                    }
                }

                is NetworkResult.Failure -> result.error.asMuzeiBatch()
            }
        }

        companion object {
            /**
             * Upper bound on one batch: keeps the binder transaction to
             * Muzei bounded on pathological libraries (the whole batch
             * crosses the process boundary in one parcel). The most recent
             * saves form the carousel window.
             */
            const val MAX_ARTWORKS = 200

            const val FIRST_PAGE = 1
        }
    }

/**
 * The content ratings the browse pipeline can show under the SFW-only
 * setting — the same vocabulary the Muzei carousel honors. NSFW is never
 * requestable in V1.
 */
internal fun allowedRatings(sfwOnly: Boolean): Set<ContentRating> =
    if (sfwOnly) {
        setOf(ContentRating.SFW)
    } else {
        setOf(ContentRating.SFW, ContentRating.SKETCHY)
    }

/**
 * Transport failures (offline, timeout, HTTP) are worth a retry with
 * backoff; source failures are not — retrying a broken plugin changes
 * nothing, and Muzei keeps showing its current artwork either way.
 */
internal fun NetworkError.asMuzeiBatch(): MuzeiBatch =
    when (this) {
        is NetworkError.Io, is NetworkError.Timeout, is NetworkError.Http ->
            MuzeiBatch.Retryable(reason = toString())

        is NetworkError.Serialization, is NetworkError.Source -> MuzeiBatch.Empty
    }

/** Cyclic rotation: [start] is taken modulo size; empty lists pass through. */
internal fun <T> List<T>.rotateBy(start: Int): List<T> {
    if (isEmpty()) return this
    val index = start.mod(size)
    return drop(index) + take(index)
}
