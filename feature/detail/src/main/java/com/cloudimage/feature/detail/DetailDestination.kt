package com.cloudimage.feature.detail

import com.cloudimage.core.model.Wallpaper
import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * Route contract for the detail feature.
 *
 * The whole [Wallpaper] travels as a Base64url-encoded JSON argument — that
 * alphabet contains only [A-Za-z0-9_-], so the value survives navigation
 * verbatim with no percent-encoding ambiguity, and the screen keeps working
 * after process death (SavedStateHandle holds the encoded string).
 */
object DetailDestination {
    const val route = "detail/{wallpaper}"
    const val arg = "wallpaper"

    private val json = Json { ignoreUnknownKeys = true }

    fun createRoute(wallpaper: Wallpaper): String = "detail/${encode(wallpaper)}"

    /** Serializes the wallpaper into a navigation-safe route argument. */
    fun encode(wallpaper: Wallpaper): String =
        Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(json.encodeToString(Wallpaper.serializer(), wallpaper).toByteArray())

    /** Parses the route argument back into a [Wallpaper], or null when invalid. */
    fun decode(value: String?): Wallpaper? =
        value?.let { raw ->
            runCatching {
                val bytes = Base64.getUrlDecoder().decode(raw)
                json.decodeFromString(Wallpaper.serializer(), String(bytes))
            }.getOrNull()
        }
}
