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
    override fun createFor(packageFile: File): ClassLoader =
        // optimizedDirectory is deprecated and ignored from API 26, our minSdk.
        DexClassLoader(
            packageFile.path,
            null,
            null,
            javaClass.classLoader,
        )
}
