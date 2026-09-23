package com.cloudimage.core.data.platform

import android.app.WallpaperManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.VisibleForTesting
import com.cloudimage.core.data.repository.ApplyError
import com.cloudimage.core.data.repository.ApplyResult
import com.cloudimage.core.data.repository.ApplyTarget
import com.cloudimage.core.data.repository.SaveError
import com.cloudimage.core.data.repository.SaveResult
import com.cloudimage.core.data.repository.WallpaperApplier
import com.cloudimage.core.data.repository.WallpaperSaver
import com.cloudimage.core.model.Wallpaper
import com.cloudimage.core.model.savedFileName
import com.cloudimage.core.model.savedMimeType
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

/**
 * Thin seam over [WallpaperManager]: the one class in the apply pipeline that
 * touches the framework directly. Everything around it is unit-tested; this
 * seam exists so tests can inject failures (unsupported device, IO errors)
 * instead of depending on Robolectric's wallpaper shadow.
 */
fun interface SystemWallpaperSetter {
    /**
     * Sets [bitmap] as the wallpaper for [target].
     *
     * @throws IllegalArgumentException when the device does not support the target
     * @throws SecurityException when the app is not allowed to set that target
     * @throws IOException on system-level write failures
     */
    fun set(
        bitmap: Bitmap,
        target: ApplyTarget,
    )
}

/** Production [SystemWallpaperSetter] backed by the framework WallpaperManager. */
@Singleton
class WallpaperManagerSetter
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : SystemWallpaperSetter {
        override fun set(
            bitmap: Bitmap,
            target: ApplyTarget,
        ) {
            val manager = WallpaperManager.getInstance(context)
            manager.setBitmap(bitmap, null, true, target.toManagerFlags())
        }
    }

/**
 * Seam over BitmapFactory: Robolectric decodes arbitrary bytes into bitmap
 * stubs, so real decoding cannot be asserted in JVM tests — the decode
 * failure path is exercised by injecting a null-returning decoder instead.
 */
fun interface BitmapDecoder {
    fun decode(bytes: ByteArray): Bitmap?
}

/** Production [BitmapDecoder] backed by BitmapFactory. */
@Singleton
class BitmapFactoryDecoder
    @Inject
    constructor() : BitmapDecoder {
        override fun decode(bytes: ByteArray): Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

/** Maps an [ApplyTarget] onto WallpaperManager FLAG_SYSTEM / FLAG_LOCK. */
@VisibleForTesting
internal fun ApplyTarget.toManagerFlags(): Int =
    when (this) {
        ApplyTarget.HOME -> WallpaperManager.FLAG_SYSTEM
        ApplyTarget.LOCK -> WallpaperManager.FLAG_LOCK
        ApplyTarget.BOTH -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
    }

/**
 * Downloads, decodes and applies wallpapers. Network via the shared
 * [CloudimageHttpClient]; the bitmap decode and the system call go through
 * small seams ([BitmapDecoder], [SystemWallpaperSetter]) so every failure mode
 * stays observable in tests.
 */
@Singleton
class AndroidWallpaperApplier
    @Inject
    constructor(
        private val httpClient: CloudimageHttpClient,
        private val decoder: BitmapDecoder,
        private val setter: SystemWallpaperSetter,
    ) : WallpaperApplier {
        override suspend fun apply(
            wallpaper: Wallpaper,
            target: ApplyTarget,
        ): ApplyResult =
            withContext(Dispatchers.IO) {
                when (val response = httpClient.download(wallpaper.fullUrl)) {
                    is NetworkResult.Failure -> ApplyResult.Failure(response.error.toApplyError())
                    is NetworkResult.Success -> {
                        val bitmap = decoder.decode(response.value)
                        if (bitmap == null) {
                            ApplyResult.Failure(ApplyError.DECODE)
                        } else {
                            try {
                                setter.set(bitmap, target)
                                ApplyResult.Success
                            } catch (e: IllegalArgumentException) {
                                ApplyResult.Failure(ApplyError.UNSUPPORTED)
                            } catch (e: SecurityException) {
                                ApplyResult.Failure(ApplyError.UNSUPPORTED)
                            } catch (e: IOException) {
                                ApplyResult.Failure(ApplyError.IO)
                            }
                        }
                    }
                }
            }
    }

@VisibleForTesting
internal fun NetworkError.toApplyError(): ApplyError =
    when (this) {
        is NetworkError.Http -> ApplyError.HTTP
        NetworkError.Timeout -> ApplyError.TIMEOUT
        is NetworkError.Io -> ApplyError.OFFLINE
        is NetworkError.Serialization -> ApplyError.HTTP
    }

@VisibleForTesting
internal fun NetworkError.toSaveError(): SaveError =
    when (this) {
        is NetworkError.Http -> SaveError.HTTP
        NetworkError.Timeout -> SaveError.TIMEOUT
        is NetworkError.Io -> SaveError.OFFLINE
        is NetworkError.Serialization -> SaveError.HTTP
    }

/** Where gallery saves land on API 29+. */
@VisibleForTesting
internal const val GALLERY_RELATIVE_PATH = "Pictures/Cloudimage"

/** Subdirectory of cacheDir where share staging files are written. */
@VisibleForTesting
internal const val SHARE_DIR_NAME = "shared"

/**
 * Persists wallpapers via MediaStore (API 29+, no permissions needed) or the
 * app's external Pictures dir on API 26–28 (no WRITE_EXTERNAL_STORAGE flow in
 * V1; the file is visible to file managers and USB transfers, and a future
 * release can add the runtime permission).
 */
@Singleton
class MediaStoreWallpaperSaver
    @Inject
    constructor(
        private val httpClient: CloudimageHttpClient,
        @ApplicationContext private val context: Context,
    ) : WallpaperSaver {
        override suspend fun saveToGallery(wallpaper: Wallpaper): SaveResult =
            withContext(Dispatchers.IO) {
                val bytes =
                    when (val response = httpClient.download(wallpaper.fullUrl)) {
                        is NetworkResult.Failure -> return@withContext SaveResult.Failure(response.error.toSaveError())
                        is NetworkResult.Success -> response.value
                    }
                val fileName = wallpaper.savedFileName()
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        insertIntoMediaStore(fileName, wallpaper.savedMimeType(), bytes)
                    } else {
                        writeLegacyPicturesFile(fileName, bytes)
                    }
                } catch (e: IOException) {
                    SaveResult.Failure(SaveError.IO)
                }
            }

        override suspend fun prepareShareFile(wallpaper: Wallpaper): SaveResult =
            withContext(Dispatchers.IO) {
                val bytes =
                    when (val response = httpClient.download(wallpaper.fullUrl)) {
                        is NetworkResult.Failure -> return@withContext SaveResult.Failure(response.error.toSaveError())
                        is NetworkResult.Success -> response.value
                    }
                val fileName = wallpaper.savedFileName()
                try {
                    val dir = File(context.cacheDir, SHARE_DIR_NAME).apply { mkdirs() }
                    val file = File(dir, fileName)
                    file.outputStream().use { output -> output.write(bytes) }
                    SaveResult.Success(uri = file.absolutePath, fileName = fileName)
                } catch (e: IOException) {
                    SaveResult.Failure(SaveError.IO)
                }
            }

        private fun insertIntoMediaStore(
            fileName: String,
            mimeType: String,
            bytes: ByteArray,
        ): SaveResult {
            val resolver = context.contentResolver
            val uri =
                resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, galleryContentValues(fileName, mimeType))
                    ?: return SaveResult.Failure(SaveError.IO)
            try {
                resolver.openOutputStream(uri)?.use { output -> output.write(bytes) }
                    ?: return cleanUp(uri, SaveResult.Failure(SaveError.IO))
                val published = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                resolver.update(uri, published, null, null)
                return SaveResult.Success(uri = uri.toString(), fileName = fileName)
            } catch (e: IOException) {
                return cleanUp(uri, SaveResult.Failure(SaveError.IO))
            }
        }

        /** Deletes the half-written MediaStore row before reporting failure. */
        private fun cleanUp(
            uri: Uri,
            result: SaveResult,
        ): SaveResult {
            runCatching { context.contentResolver.delete(uri, null, null) }
            return result
        }

        private fun writeLegacyPicturesFile(
            fileName: String,
            bytes: ByteArray,
        ): SaveResult {
            val dir =
                context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                    ?: File(context.filesDir, Environment.DIRECTORY_PICTURES).apply { mkdirs() }
            val file = File(dir, fileName)
            file.outputStream().use { output -> output.write(bytes) }
            return SaveResult.Success(uri = Uri.fromFile(file).toString(), fileName = fileName)
        }
    }

/** ContentValues describing one pending gallery insert (API 29+). */
@VisibleForTesting
internal fun galleryContentValues(
    fileName: String,
    mimeType: String,
): ContentValues =
    ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
        put(MediaStore.Images.Media.MIME_TYPE, mimeType)
        put(MediaStore.Images.Media.RELATIVE_PATH, GALLERY_RELATIVE_PATH)
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
