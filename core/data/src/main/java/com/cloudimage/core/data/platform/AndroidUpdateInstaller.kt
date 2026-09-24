package com.cloudimage.core.data.platform

import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import androidx.core.content.FileProvider
import com.cloudimage.core.data.repository.UpdateInstallError
import com.cloudimage.core.data.repository.UpdateInstallResult
import com.cloudimage.core.data.repository.UpdateInstaller
import com.cloudimage.core.model.AppUpdate
import com.cloudimage.core.network.CloudimageHttpClient
import com.cloudimage.core.network.NetworkError
import com.cloudimage.core.network.NetworkResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Fires the system installer for a downloaded APK uri. */
fun interface InstallerStarter {
    fun start(intent: Intent)
}

/**
 * Downloads the release APK through the shared HTTP pipeline, stages it in
 * cache, and hands it to the system package installer through a
 * FileProvider uri.
 *
 * [starter] is the framework seam — tests capture the intent instead of
 * actually launching the installer (Robolectric has no package installer).
 */
@Singleton
class AndroidUpdateInstaller
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val client: CloudimageHttpClient,
    ) : UpdateInstaller {
        @VisibleForTesting
        internal var starter: InstallerStarter = InstallerStarter { intent -> context.startActivity(intent) }

        override suspend fun downloadAndInstall(update: AppUpdate): UpdateInstallResult =
            withContext(Dispatchers.IO) {
                val bytes =
                    when (val download = client.download(update.apkUrl)) {
                        is NetworkResult.Failure ->
                            return@withContext UpdateInstallResult.Failure(download.error.toInstallError())

                        is NetworkResult.Success -> download.value
                    }
                install(update, bytes)
            }

        private fun install(
            update: AppUpdate,
            bytes: ByteArray,
        ): UpdateInstallResult {
            if (bytes.isEmpty()) return UpdateInstallResult.Failure(UpdateInstallError.IO)
            return try {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                val file = File(dir, "cloudimage-${update.tagName.sanitized()}.apk")
                file.writeBytes(bytes)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.updateprovider", file)
                starter.start(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, APK_MIME)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                UpdateInstallResult.Started
            } catch (e: IOException) {
                UpdateInstallResult.Failure(UpdateInstallError.IO)
            } catch (e: SecurityException) {
                UpdateInstallResult.Failure(UpdateInstallError.IO)
            } catch (e: IllegalArgumentException) {
                UpdateInstallResult.Failure(UpdateInstallError.IO)
            }
        }

        private fun NetworkError.toInstallError(): UpdateInstallError =
            when (this) {
                is NetworkError.Http -> UpdateInstallError.HTTP
                NetworkError.Timeout -> UpdateInstallError.TIMEOUT
                is NetworkError.Io -> UpdateInstallError.OFFLINE
                is NetworkError.Serialization -> UpdateInstallError.IO
            }

        private fun String.sanitized(): String = filter { it.isLetterOrDigit() || it == '.' || it == '-' }.ifEmpty { "latest" }

        private companion object {
            const val APK_MIME = "application/vnd.android.package-archive"
        }
    }
