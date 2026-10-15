package com.cloudimage.extensions.core

import dalvik.system.DexClassLoader
import java.io.File

/**
 * Seam between the engine and the platform classloader.
 *
 * Production loads real dex payloads through [DexClassLoader]; tests
 * substitute URL classloaders over JVM class payloads to exercise the
 * full load path (class lookup, instantiation, casting, configuration)
 * on the JVM without a device.
 */
fun interface ExtensionClassLoaderFactory {
    /** Returns a classloader over [packageFile]'s payload. */
    fun createFor(packageFile: File): ClassLoader
}

/** Production implementation: dex payloads from installed package zips. */
class DexExtensionClassLoaderFactory : ExtensionClassLoaderFactory {
    override fun createFor(packageFile: File): ClassLoader {
        // Android 14+ (enforced for host apps targeting API 34+) refuses to
        // open a WRITABLE dex container - DexClassLoader throws SecurityException
        // and every source shows as failed-to-load on Android 14/15/16 devices.
        // The v1.2.15 field report (Samsung, Android 16): installs succeed, loads
        // all fail. Heal the file here so packages installed by v1.2.15-and-older
        // (which left them writable) load without a reinstall; the installer now
        // marks new packages read-only at swap-in time as well.
        packageFile.markReadOnlyBeforeDexLoad()
        // optimizedDirectory is deprecated and ignored from API 26, our minSdk.
        return DexClassLoader(
            packageFile.path,
            null,
            null,
            javaClass.classLoader,
        )
    }
}

/**
 * Android 14+ requires a dynamically loaded dex container to be read-only at
 * open time. Best-effort and idempotent: true when the file is (now) safe to
 * hand to DexClassLoader - already read-only, healed, or absent (the loader
 * will produce its own typed failure for a missing package, not ours).
 */
internal fun File.markReadOnlyBeforeDexLoad(): Boolean = !exists() || !canWrite() || setWritable(false)
