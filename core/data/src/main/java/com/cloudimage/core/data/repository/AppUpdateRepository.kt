package com.cloudimage.core.data.repository

import com.cloudimage.core.model.AppUpdate
import com.cloudimage.core.network.CloudimageHttpClient
import com.cloudimage.core.network.NetworkError
import com.cloudimage.core.network.NetworkResult
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Failure taxonomy for the download-and-install leg of an update.
 * Mirrors [com.cloudimage.core.data.repository.SaveError].
 */
enum class UpdateInstallError {
    OFFLINE,
    TIMEOUT,
    HTTP,

    /** Writing the APK to cache or handing it to the system failed. */
    IO,
}

/** Result of the install flow: either the system installer came up, or not. */
sealed interface UpdateInstallResult {
    /** The system package installer UI was started successfully. */
    data object Started : UpdateInstallResult

    data class Failure(
        val error: UpdateInstallError,
    ) : UpdateInstallResult
}

/**
 * Downloads the release APK and hands it to the system package installer.
 * Implemented in :app where the FileProvider + install intents live.
 */
interface UpdateInstaller {
    suspend fun downloadAndInstall(update: AppUpdate): UpdateInstallResult
}

/** Fetches the newest released version of the app. */
interface AppUpdateRepository {
    /** The latest GitHub release carrying an APK asset. */
    suspend fun latest(): NetworkResult<AppUpdate>
}

@Serializable
internal data class GitHubReleaseDto(
    val tag_name: String,
    val name: String? = null,
    val html_url: String,
    val assets: List<GitHubAssetDto> = emptyList(),
)

@Serializable
internal data class GitHubAssetDto(
    val name: String,
    val browser_download_url: String,
    val size: Long = 0,
    val content_type: String? = null,
)

/** Picks the APK asset out of a release and maps it to [AppUpdate]. */
internal fun GitHubReleaseDto.toAppUpdate(): NetworkResult<AppUpdate> {
    val apk =
        assets.firstOrNull {
            it.content_type == APK_MIME || it.name.endsWith(".apk", ignoreCase = true)
        }
    return if (apk == null) {
        NetworkResult.Failure(
            NetworkError.Serialization(
                IllegalStateException("Release $tag_name has no APK asset"),
            ),
        )
    } else {
        NetworkResult.Success(
            AppUpdate(
                tagName = tag_name,
                versionName = cleanVersion(tag_name),
                apkUrl = apk.browser_download_url,
                apkSizeBytes = apk.size,
                releaseUrl = html_url,
                title = name,
            ),
        )
    }
}

/**
 * Reads `releases/latest` from GitHub and picks the APK asset.
 *
 * GitHub's API rate-limits anonymous clients (60/hour per IP), so the UI
 * checks once per process and only re-checks on explicit request.
 */
@Singleton
class GitHubAppUpdateRepository
    @Inject
    constructor(
        private val client: CloudimageHttpClient,
    ) : AppUpdateRepository {
        override suspend fun latest(): NetworkResult<AppUpdate> =
            when (val release = client.getJson(RELEASES_URL, GitHubReleaseDto.serializer())) {
                is NetworkResult.Failure -> release
                is NetworkResult.Success -> release.value.toAppUpdate()
            }

        private companion object {
            const val RELEASES_URL =
                "https://api.github.com/repos/alamsamir7666-ux/Cloud-Wallpaper/releases/latest"
        }
    }

/**
 * True when [remote] is a numerically newer dotted version than [current].
 *
 * Tolerates a leading "v"/"V", and a "-" suffix segment (prerelease or
 * build noise): "v1.2.0-beta" is compared as 1.2.0. Missing parts count as
 * zero, non-numeric parts as zero too ("1.2.x" == "1.2.0").
 */
fun isVersionNewer(
    remote: String,
    current: String,
): Boolean {
    fun numericParts(version: String): List<Int> =
        version
            .trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('-')
            .split('.')
            .map { part ->
                part
                    .filter(Char::isDigit)
                    .ifEmpty { "0" }
                    .take(NUMERIC_PART_LIMIT)
                    .toInt()
            }

    val remoteParts = numericParts(remote)
    val currentParts = numericParts(current)
    val depth = maxOf(remoteParts.size, currentParts.size)
    for (i in 0 until depth) {
        val remotePart = remoteParts.getOrElse(i) { 0 }
        val currentPart = currentParts.getOrElse(i) { 0 }
        if (remotePart != currentPart) return remotePart > currentPart
    }
    return false
}

/** "v1.2.0" -> "1.2.0"; anything without the prefix passes through. */
private fun cleanVersion(tag: String): String =
    tag
        .trim()
        .removePrefix("v")
        .removePrefix("V")
        .ifEmpty { tag }

internal const val APK_MIME = "application/vnd.android.package-archive"

private const val NUMERIC_PART_LIMIT = 6
