package com.cloudimage.provider.api

/**
 * One category of an album-style provider (v1.1.0) — the top level of the
 * album paradigm: a source whose content is organized as
 * categories → albums → wallpapers instead of flat feeds.
 *
 * The host renders categories as the album UI's sidebar: [iconEmoji] is
 * shown inside a tinted circle with [name] underneath, exactly like the
 * source's own website navigation when it ships one (WallpaperAccess
 * does). A provider without emoji can leave it null and the host falls
 * back to a generic icon; [coverUrl], when present, lets richer hosts
 * render a photo tile instead.
 *
 * Categories should be cheap and stable — the host asks for them once
 * per album session and keeps them for the sidebar.
 */
data class Category(
    /** Stable identifier of the category inside its provider. */
    val id: String,
    /** Display name, e.g. "Anime". */
    val name: String,
    /** The category's own icon when the source ships one, e.g. "💥". */
    val iconEmoji: String? = null,
    /** Optional cover image for richer renderings. */
    val coverUrl: String? = null,
)

/**
 * One album of an album-style provider (v1.1.0) — a titled collection of
 * wallpapers, the middle level of the paradigm. On the sources this models
 * (WallpaperAccess and its kin) an album is a curated topic ("Attack On
 * Titan", 70 wallpapers) with a cover image and a count.
 *
 * [coverUrl] should be small (grid-sized); [wallpaperCount] is advisory —
 * the badge the album card shows, and the truth is whatever
 * [WallpaperProvider.albumWallpapers] returns.
 */
data class Album(
    /** Stable identifier of the album inside its provider (its slug). */
    val id: String,
    /** The provider this album belongs to. */
    val providerId: String,
    /** Display title, e.g. "Attack On Titan". */
    val title: String,
    /** Grid-sized cover image. */
    val coverUrl: String,
    /** How many wallpapers the album holds, when the source discloses it. */
    val wallpaperCount: Int = 0,
)
