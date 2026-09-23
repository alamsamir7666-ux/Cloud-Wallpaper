package com.cloudimage.extensions.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ExtensionScannerTest {
    @get:Rule
    val tempDir = TemporaryFolder()

    private lateinit var dirs: ExtensionDirs
    private lateinit var index: ExtensionIndex
    private lateinit var installer: ExtensionInstaller
    private lateinit var scanner: ExtensionScanner

    @Before
    fun setUp() {
        dirs = ExtensionDirs(File(tempDir.root, "ext")).ensure()
        index = ExtensionIndex(dirs.indexFile)
        installer = ExtensionInstaller(dirs, index)
        scanner = ExtensionScanner(dirs, index)
    }

    @Test
    fun emptyAreaScansToEmptyList() {
        assertTrue(scanner.scan().isEmpty())
    }

    @Test
    fun installedExtensionIsReady() {
        val source = TestPackages.packageExtension(tempDir.root)
        installer.install(source)

        val row = scanner.scan().single()

        assertEquals("cloudimage.demo", row.id)
        assertEquals(ExtensionStatus.READY, row.status)
        assertEquals("cloudimage.demo.zip", row.fileName)
        assertEquals("Demo Walls", row.manifest!!.name)
        assertEquals(Sha256.of(dirs.packageFile(row.fileName)), row.sha256)
    }

    @Test
    fun tamperedPackageIsCorrupted() {
        val source = TestPackages.packageExtension(tempDir.root)
        installer.install(source)
        dirs.packageFile("cloudimage.demo.zip").appendBytes(byteArrayOf(0x63, 0x6f, 0x72, 0x72, 0x75, 0x70, 0x74))

        val row = scanner.scan().single()

        assertEquals(ExtensionStatus.CORRUPTED, row.status)
    }

    @Test
    fun replacedPackageWithDifferentContentIsCorrupted() {
        val first = TestPackages.packageExtension(tempDir.root)
        installer.install(first)
        val replacement =
            TestPackages.packageExtension(
                tempDir.root,
                manifest = TestPackages.manifestJson(versionName = "9.9.9", versionCode = 99),
            )
        // Drop-in with different content under the same file name — the
        // checksum recorded at install time no longer matches.
        replacement.copyTo(dirs.packageFile("cloudimage.demo.zip"), overwrite = true)

        val row = scanner.scan().single()

        assertEquals(ExtensionStatus.CORRUPTED, row.status)
        assertEquals("9.9.9", row.manifest!!.versionName)
    }

    @Test
    fun unindexedPackageIsUntrusted() {
        val orphan =
            TestPackages.packageExtension(
                dirs.packagesDir,
                manifest = TestPackages.manifestJson(),
                fileName = "mystery.zip",
            )
        assertTrue(orphan.isFile)

        val row = scanner.scan().single()

        assertEquals(ExtensionStatus.UNTRUSTED, row.status)
        assertEquals("cloudimage.demo", row.id)
        assertEquals("mystery.zip", row.fileName)
        assertEquals("Demo Walls", row.manifest!!.name)
    }

    @Test
    fun staleIndexEntriesForMissingFilesAreDropped() {
        val source = TestPackages.packageExtension(tempDir.root)
        installer.install(source)
        dirs.packageFile("cloudimage.demo.zip").delete()

        assertTrue(scanner.scan().isEmpty())
    }

    @Test
    fun unreadableManifestSurfacesAsCorrupted() {
        // Indexed by hand but the package zip is garbage.
        index.write(
            mapOf(
                "cloudimage.demo" to
                    ExtensionIndexEntry(
                        fileName = "broken.zip",
                        sha256 = Sha256.of("whatever".toByteArray()),
                    ),
            ),
        )
        File(dirs.packagesDir, "broken.zip").writeText("this is not a zip file")

        val row = scanner.scan().single()

        assertEquals(ExtensionStatus.CORRUPTED, row.status)
        assertEquals("cloudimage.demo", row.id)
        assertEquals(null, row.manifest)
    }
}
