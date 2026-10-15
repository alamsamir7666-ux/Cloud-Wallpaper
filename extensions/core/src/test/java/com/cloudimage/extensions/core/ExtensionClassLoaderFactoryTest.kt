package com.cloudimage.extensions.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The Android 14+ dex-loading rule (host targetSdk 34+): a dynamically
 * loaded dex container must be read-only at open time, or DexClassLoader
 * throws SecurityException. The load-time healer is plain java.io, so it
 * is JVM-testable without a device — the field report behind v1.2.16 was
 * exactly this: installs succeed, every load fails, on Android 14+ only.
 */
class ExtensionClassLoaderFactoryTest {
    @get:Rule
    val tempDir = TemporaryFolder()

    @Test
    fun healsAPackageLeftWritableByAnOlderVersion() {
        val file = tempDir.newFile("cloudimage.demo.zip")

        val healed = file.markReadOnlyBeforeDexLoad()

        assertTrue(healed)
        assertFalse(file.canWrite())
    }

    @Test
    fun readOnlyPackageIsAlreadySafe() {
        val file =
            tempDir.newFile("cloudimage.demo.zip").apply {
                setWritable(false)
            }

        val healed = file.markReadOnlyBeforeDexLoad()

        assertTrue(healed)
        assertFalse(file.canWrite())
    }

    @Test
    fun missingPackageIsNotOursToFailOn() {
        // A vanished package must surface the loader's own typed failure,
        // not a healer error — the healer stays out of the diagnosis path.
        val ghost = File(tempDir.root, "cloudimage.ghost.zip")

        assertTrue(ghost.markReadOnlyBeforeDexLoad())
    }
}
