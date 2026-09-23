package com.cloudimage.core.model

/*
 * Storage metadata derived from a [Wallpaper]: safe file names and mime
 * types for saving, sharing and intent extras.
 */

/** Derives a safe file name: "wallhaven-e1abc2.jpg" — provider and id give uniqueness, the URL extension gives the type. */
fun Wallpaper.savedFileName(): String = "$providerId-${id.sanitized()}.${savedExtension()}"

/** Derives the mime type from the full URL's extension, defaulting to JPEG. */
fun Wallpaper.savedMimeType(): String = "image/${savedExtension().replace("jpg", "jpeg")}"

/** Extracts the image extension from the full URL, defaulting to "jpg". */
fun Wallpaper.savedExtension(): String {
    val segment = fullUrl.substringBeforeLast('?').substringAfterLast('/')
    val extension = segment.substringAfterLast('.', missingDelimiterValue = "")
    return if (extension.matches(Regex("[a-zA-Z0-9]{2,5}"))) extension.lowercase() else "jpg"
}

/** Keeps only filesystem-safe characters. */
private fun String.sanitized(): String = map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
