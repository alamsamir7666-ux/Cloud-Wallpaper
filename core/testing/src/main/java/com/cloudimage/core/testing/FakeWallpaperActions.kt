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
    var galleryResult: SaveResult = SaveResult.Success(uri = "content://media/42", fileName = "wallhaven-e1abc2.jpg")
    var shareResult: SaveResult = SaveResult.Success(uri = "/cache/shared/wallhaven-e1abc2.jpg", fileName = "wallhaven-e1abc2.jpg")

    override suspend fun saveToGallery(wallpaper: Wallpaper): SaveResult {
        galleryCalls += wallpaper
        return galleryResult
    }

    override suspend fun prepareShareFile(wallpaper: Wallpaper): SaveResult {
        shareCalls += wallpaper
        return shareResult
    }
}
