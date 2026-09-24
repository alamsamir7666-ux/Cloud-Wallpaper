package com.cloudimage.core.testing

import com.cloudimage.core.data.rotation.RotationResult
import com.cloudimage.core.data.rotation.WallpaperRotator
import com.cloudimage.core.model.RotationTarget

/**
 * Test double for the rotation port: script outcomes, inspect the targets
 * each rotation was asked to apply to.
 */
class FakeWallpaperRotator : WallpaperRotator {
    val targets = mutableListOf<RotationTarget>()
    var result: RotationResult = RotationResult.NoWallpapers

    override suspend fun rotateOnce(target: RotationTarget): RotationResult {
        targets += target
        return result
    }
}
