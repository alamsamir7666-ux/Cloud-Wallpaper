package com.cloudimage.core.model

/**
 * A released app version, as described by the GitHub Releases API.
 *
 * The app is distributed through GitHub Releases, so "checking for updates"
 * means reading `releases/latest` and comparing its [tagName] with the
 * running build's version name.
 */
data class AppUpdate(
    /** Release tag, e.g. "v1.2.0". */
    val tagName: String,
    /** The tag with any leading "v" stripped — "1.2.0", for display. */
    val versionName: String,
    /** Direct download URL of the release APK asset. */
    val apkUrl: String,
    /** APK size in bytes, for showing before the download starts. */
    val apkSizeBytes: Long,
    /** The human-facing release page. */
    val releaseUrl: String,
    /** Release title, e.g. "Cloudimage 1.2.0". */
    val title: String? = null,
)
