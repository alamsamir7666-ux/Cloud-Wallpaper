package com.cloudimage.core.testing

import com.cloudimage.core.data.repository.ApplyResult
import com.cloudimage.core.data.repository.ApplyTarget
import com.cloudimage.core.data.repository.SaveResult
import com.cloudimage.core.data.repository.WallpaperApplier
import com.cloudimage.core.data.repository.WallpaperSaver
import com.cloudimage.core.model.Wallpaper
import kotlinx.coroutines.CompletableDeferred

/**
 * Test doubles for the wallpaper action ports, following the same contract as
 * the other fakes: drive them through the interface, script outcomes and
 * inspect recorded calls through the test hooks.
 *
 * [gate] lets a test suspend an in-flight operation, so "running" states and
 * re-entrancy guards can be asserted deterministically.
 */
class FakeWallpaperApplier : WallpaperApplier {
    val calls = mutableListOf<Pair<Wallpaper, ApplyTarget>>()
    var result: ApplyResult = ApplyResult.Success
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun apply(
        wallpaper: Wallpaper,
        target: ApplyTarget,
    ): ApplyResult {
        calls += wallpaper to target
        gate?.await()
        return result
    }
}

class FakeWallpaperSaver : WallpaperSaver {
    val galleryCalls = mutableListOf<Wallpaper>()
    val shareCalls = mutableListOf<Wallpaper>()

    /** Every progress pair emitted by the last (or scripted) gallery save. */
    val emittedProgress = mutableListOf<Pair<Long, Long?>>()

    /** What a gallery save reports, in order; defaults to a quick 2-tick run. */
    var progressScript: List<Pair<Long, Long?>> = listOf(2_000L to 4_000L, 4_000L to 4_000L)

    /** Suspends a gallery save AFTER its progress ticks — lets tests observe mid-flight state. */
    var gate: CompletableDeferred<Unit>? = null

    var galleryResult: SaveResult = SaveResult.Success(uri = "content://media/42", fileName = "wallhaven-e1abc2.jpg")
    var shareResult: SaveResult = SaveResult.Success(uri = "/cache/shared/wallhaven-e1abc2.jpg", fileName = "wallhaven-e1abc2.jpg")

    override suspend fun saveToGallery(
        wallpaper: Wallpaper,
        onProgress: (bytesRead: Long, totalBytes: Long?) -> Unit,
    ): SaveResult {
        galleryCalls += wallpaper
        emittedProgress.clear()
        progressScript.forEach { (bytes, total) ->
            emittedProgress += bytes to total
            onProgress(bytes, total)
        }
        gate?.await()
        return galleryResult
    }

    override suspend fun prepareShareFile(wallpaper: Wallpaper): SaveResult {
        shareCalls += wallpaper
        return shareResult
    }
}
