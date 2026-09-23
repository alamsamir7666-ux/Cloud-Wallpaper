package com.cloudimage.extensions.core

import com.cloudimage.provider.api.ProviderHttpResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ExtensionRepositoryTest {
    @get:Rule
    val tempDir = TemporaryFolder()

    private lateinit var dirs: ExtensionDirs
    private lateinit var repository: DefaultExtensionRepository

    @Before
    fun setUp() {
        dirs = ExtensionDirs(File(tempDir.root, "ext"))
        val index = ExtensionIndex(dirs.indexFile)
        val httpClient =
            FakeProviderHttpClient(
                mapOf(
                    "demo://catalog/1" to ProviderHttpResponse(200, emptyMap(), "demo-ok".toByteArray()),
                ),
            )
        repository =
            DefaultExtensionRepository(
                installer = ExtensionInstaller(dirs, index),
                scanner = ExtensionScanner(dirs, index),
                loader = ExtensionLoader(dirs, UrlClassLoaderFactory(), httpClient),
                ioDispatcher = UnconfinedTestDispatcher(),
            )
    }

    @Test
    fun installedIsNullUntilFirstRefresh() =
        runTest {
            assertNull(repository.installed.value)

            repository.refresh()

            assertEquals(emptyList<InstalledExtension>(), repository.installed.value)
        }

    @Test
    fun installMakesRowVisible() =
        runTest {
            val source = TestPackages.packageExtension(tempDir.root)

            val result = repository.install(source)

            assertTrue(result is InstallResult.Installed)
            val row = repository.installed.value!!.single()
            assertEquals("cloudimage.demo", row.id)
            assertEquals(ExtensionStatus.READY, row.status)
        }

    @Test
    fun uninstallClearsTheRow() =
        runTest {
            repository.install(TestPackages.packageExtension(tempDir.root))
            assertTrue(repository.installed.value!!.isNotEmpty())

            val removed = repository.uninstall("cloudimage.demo")

            assertTrue(removed)
            assertEquals(emptyList<InstalledExtension>(), repository.installed.value)
        }

    @Test
    fun providerForLoadsAndCachesByChecksum() =
        runTest {
            repository.install(TestPackages.packageExtension(tempDir.root))
            val row = repository.installed.value!!.single()

            val first = repository.providerFor(row)
            val second = repository.providerFor(row)

            assertTrue(first is LoadResult.Loaded)
            assertSame((first as LoadResult.Loaded).provider, (second as LoadResult.Loaded).provider)
        }

    @Test
    fun providerForRefusesCorruptedRows() =
        runTest {
            repository.install(TestPackages.packageExtension(tempDir.root))
            dirs.packageFile("cloudimage.demo.zip").appendBytes(byteArrayOf(0x74, 0x61, 0x6d, 0x70, 0x65, 0x72))
            repository.refresh()
            val corrupted = repository.installed.value!!.single()
            assertEquals(ExtensionStatus.CORRUPTED, corrupted.status)

            val result = repository.providerFor(corrupted)

            assertEquals(
                ExtensionError.NotLoadable(ExtensionStatus.CORRUPTED),
                (result as LoadResult.Failed).error,
            )
        }
}
