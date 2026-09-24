package com.cloudimage.core.muzei

import com.cloudimage.core.model.Wallpaper

/**
 * The artwork fields Muzei will show, as plain strings: kept free of
 * android.net.Uri so the mapping is unit-testable on the JVM. The Android
 * glue in [MuzeiArtworkWorker] turns a spec into a real Muzei
 * [com.google.android.apps.muzei.api.provider.Artwork].
 */
data class MuzeiArtworkSpec(
    /** Stable identity ("providerId/wallpaperId") so Muzei can dedupe. */
    val token: String,
    val title: String,
    val byline: String,
    val attribution: String,
    val persistentUri: String,
    val webUri: String?,
)

/** Maps a wallpaper onto the Muzei artwork vocabulary. */
fun Wallpaper.toMuzeiArtworkSpec(): MuzeiArtworkSpec =
    MuzeiArtworkSpec(
        token = "$providerId/$id",
        title = title?.takeIf { it.isNotBlank() } ?: UNTITLED,
        byline = providerId,
        attribution = ATTRIBUTION,
        persistentUri = fullUrl,
        webUri = sourceUrl,
    )

private const val UNTITLED = "Untitled"
private const val ATTRIBUTION = "Cloudimage"
