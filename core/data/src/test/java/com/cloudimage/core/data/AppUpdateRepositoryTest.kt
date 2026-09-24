package com.cloudimage.core.data

import com.cloudimage.core.data.repository.GitHubAssetDto
import com.cloudimage.core.data.repository.GitHubReleaseDto
import com.cloudimage.core.data.repository.isVersionNewer
import com.cloudimage.core.data.repository.toAppUpdate
import com.cloudimage.core.network.NetworkError
import com.cloudimage.core.network.NetworkResult
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateRepositoryTest {
    @Test
    fun parsesLatestReleaseWithApkAsset() {
        val dto = json.decodeFromString(GitHubReleaseDto.serializer(), RELEASE_JSON)

        val result = dto.toAppUpdate()

        assertTrue(result is NetworkResult.Success)
        val update = (result as NetworkResult.Success).value
        assertEquals("v1.2.0", update.tagName)
        assertEquals("1.2.0", update.versionName)
        assertEquals("https://example.com/Cloudimage-v1.2.0.apk", update.apkUrl)
        assertEquals(19_000_000L, update.apkSizeBytes)
        assertEquals("Cloudimage 1.2.0", update.title)
        assertEquals("https://github.com/alamsamir7666-ux/Cloud-Wallpaper/releases/tag/v1.2.0", update.releaseUrl)
    }

    @Test
    fun apkAssetIsPickedByExtensionWhenContentTypeIsMissing() {
        val dto =
            GitHubReleaseDto(
                tag_name = "v1.2.0",
                html_url = "https://github.com/release",
                assets =
                    listOf(
                        GitHubAssetDto(name = "checksums.txt", browser_download_url = "https://x/sums.txt"),
                        GitHubAssetDto(
                            name = "Cloudimage-v1.2.0.apk",
                            browser_download_url = "https://x/app.apk",
                            size = 42,
                        ),
                    ),
            )

        val update = (dto.toAppUpdate() as NetworkResult.Success).value
        assertEquals("https://x/app.apk", update.apkUrl)
        assertEquals(42L, update.apkSizeBytes)
    }

    @Test
    fun releaseWithoutApkFailsAsBadData() {
        val dto =
            GitHubReleaseDto(
                tag_name = "v1.2.0",
                html_url = "https://github.com/release",
                assets = listOf(GitHubAssetDto(name = "notes.txt", browser_download_url = "https://x/n.txt")),
            )

        val result = dto.toAppUpdate()
        assertTrue(result is NetworkResult.Failure)
        assertTrue((result as NetworkResult.Failure).error is NetworkError.Serialization)
    }

    @Test
    fun versionCompareFavorsNumericallyGreaterParts() {
        assertTrue(isVersionNewer(remote = "1.1.0", current = "1.0.9"))
        assertTrue(isVersionNewer(remote = "2.0", current = "1.9.9"))
        assertTrue(isVersionNewer(remote = "1.0.10", current = "1.0.9"))
        assertFalse(isVersionNewer(remote = "1.0.0", current = "1.0.0"))
        assertFalse(isVersionNewer(remote = "1.0.1", current = "1.2.0"))
    }

    @Test
    fun versionCompareStripsPrefixAndSuffixNoise() {
        assertTrue(isVersionNewer(remote = "v1.1.0", current = "V1.0.0"))
        // Prerelease suffixes are noise to the comparison: a beta of 1.2.0
        // is not newer than the released 1.2.0.
        assertFalse(isVersionNewer(remote = "1.2.0-beta.1", current = "1.2.0"))
        assertFalse(isVersionNewer(remote = "v1.0.0-beta", current = "1.0.0"))
    }

    @Test
    fun versionCompareTreatsMissingPartsAsZero() {
        assertTrue(isVersionNewer(remote = "1.0.1", current = "1"))
        assertFalse(isVersionNewer(remote = "1", current = "1.0.0"))
        assertTrue(isVersionNewer(remote = "1.0.0.1", current = "1.0"))
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        const val RELEASE_JSON =
            """
            {
              "tag_name": "v1.2.0",
              "name": "Cloudimage 1.2.0",
              "html_url": "https://github.com/alamsamir7666-ux/Cloud-Wallpaper/releases/tag/v1.2.0",
              "assets": [
                {
                  "name": "Cloudimage-v1.2.0.apk",
                  "browser_download_url": "https://example.com/Cloudimage-v1.2.0.apk",
                  "size": 19000000,
                  "content_type": "application/vnd.android.package-archive"
                },
                {
                  "name": "checksums.txt",
                  "browser_download_url": "https://example.com/checksums.txt",
                  "size": 320,
                  "content_type": "text/plain"
                }
              ],
              "draft": false,
              "prerelease": false
            }
            """
    }
}
