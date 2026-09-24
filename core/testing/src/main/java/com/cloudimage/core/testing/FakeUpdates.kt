package com.cloudimage.core.testing

import com.cloudimage.core.data.repository.AppUpdateRepository
import com.cloudimage.core.data.repository.UpdateInstallError
import com.cloudimage.core.data.repository.UpdateInstallResult
import com.cloudimage.core.data.repository.UpdateInstaller
import com.cloudimage.core.model.AppUpdate
import com.cloudimage.core.network.NetworkError
import com.cloudimage.core.network.NetworkResult

/**
 * Test doubles for the app-update flow. [FakeAppUpdateRepository] queues
 * results in order; [FakeUpdateInstaller] records the update it was asked
 * to install and queues a result (default: installer started).
 */
class FakeAppUpdateRepository : AppUpdateRepository {
    private val queue = ArrayDeque<NetworkResult<AppUpdate>>()
    private val requests = mutableListOf<Unit>()

    override suspend fun latest(): NetworkResult<AppUpdate> {
        requests += Unit
        return queue.removeFirstOrNull()
            ?: NetworkResult.Failure(NetworkError.Http(404, "releases/latest"))
    }

    /** Test hook: enqueues the next [latest] result. */
    fun enqueue(result: NetworkResult<AppUpdate>) {
        queue += result
    }

    /** How many times [latest] was called. */
    val checkCount: Int get() = requests.size
}

class FakeUpdateInstaller : UpdateInstaller {
    private val queue = ArrayDeque<UpdateInstallResult>()
    val installed = mutableListOf<AppUpdate>()

    override suspend fun downloadAndInstall(update: AppUpdate): UpdateInstallResult {
        installed += update
        return queue.removeFirstOrNull() ?: UpdateInstallResult.Started
    }

    /** Test hook: enqueues the next install result (e.g. a failure). */
    fun enqueue(result: UpdateInstallResult) {
        queue += result
    }
}

/** Builds a successful [AppUpdate] for tests. */
fun appUpdate(
    tagName: String = "v1.2.0",
    apkUrl: String = "https://example.com/app.apk",
): AppUpdate =
    AppUpdate(
        tagName = tagName,
        versionName = tagName.removePrefix("v"),
        apkUrl = apkUrl,
        apkSizeBytes = 19_000_000L,
        releaseUrl = "https://github.com/alamsamir7666-ux/Cloud-Wallpaper/releases/tag/$tagName",
        title = "Cloudimage $tagName",
    )

/** Builds a failed install result for tests. */
fun installFailure(error: UpdateInstallError = UpdateInstallError.IO): UpdateInstallResult.Failure = UpdateInstallResult.Failure(error)
