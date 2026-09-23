package com.cloudimage.extensions.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ExtensionInstallerTest {
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
    fun installWritesPackageAndIndexEntry() {
        val source = TestPackages.packageExtension(tempDir.root)

        val result = installer.install(source)

        assertTrue(result is InstallResult.Installed)
        val installed = result as InstallResult.Installed
        assertEquals("cloudimage.demo", installed.manifest.id)
        assertEquals(Sha256.of(source), installed.sha256)
        assertTrue(dirs.packageFile("cloudimage.demo.zip").isFile)
        assertEquals(
            ExtensionIndexEntry(fileName = "cloudimage.demo.zip", sha256 = installed.sha256),
            index.read()["cloudimage.demo"],
        )
    }

    @Test
    fun installRejectsChecksumMismatchAndWritesNothing() {
        val source = TestPackages.packageExtension(tempDir.root)

        val result = installer.install(source, expectedSha256 = "deadbeef")

        assertTrue(result is InstallResult.Failed)
        assertEquals(
            ExtensionError.ChecksumMismatch(expected = "deadbeef", actual = Sha256.of(source)),
            (result as InstallResult.Failed).error,
        )
        assertEquals(0, dirs.packagesDir.listFiles()?.size)
        assertTrue(index.read().isEmpty())
    }

    @Test
    fun installWithoutExpectedHashRecordsComputedHash() {
        val source = TestPackages.packageExtension(tempDir.root)

        val result = installer.install(source)

        assertEquals(Sha256.of(source), (result as InstallResult.Installed).sha256)
    }

    @Test
    fun installRejectsPackageWithoutManifest() {
        val source = TestPackages.packageManifestOnly(tempDir.root, manifest = null)

        val result = installer.install(source)

        assertTrue(result is InstallResult.Failed)
        assertTrue((result as InstallResult.Failed).error is ExtensionError.InvalidManifest)
        assertFalse(dirs.packageFile("cloudimage.demo.zip").exists())
    }

    @Test
    fun installRejectsInvalidManifest() {
        val source = TestPackages.packageExtension(tempDir.root, manifest = TestPackages.manifestJson(id = "Not Valid"))

        val result = installer.install(source)

        assertTrue((result as InstallResult.Failed).error is ExtensionError.InvalidManifest)
    }

    @Test
    fun installRejectsUnsupportedApiVersion() {
        val source = TestPackages.packageExtension(tempDir.root, manifest = TestPackages.manifestJson(apiVersion = 99))

        val result = installer.install(source)

        assertTrue(result is InstallResult.Failed)
        assertEquals(
            ExtensionError.UnsupportedApi(declared = 99, supported = com.cloudimage.provider.api.ProviderApi.VERSION),
            (result as InstallResult.Failed).error,
        )
    }

    @Test
    fun reinstallReplacesPackageContentAndIndex() {
        val first = TestPackages.packageExtension(tempDir.root, manifest = TestPackages.manifestJson(versionName = "1.0.0"))
        val second =
            TestPackages.packageExtension(
                tempDir.root,
                manifest = TestPackages.manifestJson(versionName = "2.0.0", versionCode = 2),
                fileName = "v2.zip",
            )
        installer.install(first)

        val result = installer.install(second)

        assertTrue(result is InstallResult.Installed)
        val rows = scanner.scan()
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(ExtensionStatus.READY, row.status)
        assertEquals("2.0.0", row.manifest!!.versionName)
        assertEquals((result as InstallResult.Installed).sha256, row.sha256)
    }

    @Test
    fun uninstallRemovesPackageAndIndexEntry() {
        val source = TestPackages.packageExtension(tempDir.root)
        installer.install(source)

        val removed = installer.uninstall("cloudimage.demo")

        assertTrue(removed)
        assertFalse(dirs.packageFile("cloudimage.demo.zip").exists())
        assertTrue(index.read().isEmpty())
        assertTrue(scanner.scan().isEmpty())
    }

    @Test
    fun uninstallOfUnknownExtensionIsFalse() {
        assertFalse(installer.uninstall("cloudimage.nope"))
    }
}
