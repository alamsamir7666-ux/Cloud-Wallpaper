package com.cloudimage.extensions.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RepoStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = RepoStore(tmp.newFile())

    @Test
    fun `reads empty when the file is missing`() {
        assertTrue(store().read().isEmpty())
    }

    @Test
    fun `upsert then read round-trips`() {
        val store = store()
        val repo = StoredRepo(id = "u1", url = "u1", name = "One", addedAtMillis = 5)

        store.upsert(repo)

        assertEquals(listOf(repo), store.read())
        assertEquals(repo, store.find("u1"))
        assertNull(store.find("other"))
    }

    @Test
    fun `upsert with the same id replaces the row`() {
        val store = store()
        store.upsert(StoredRepo(id = "u1", url = "u1", name = "One", addedAtMillis = 5))
        val updated = StoredRepo(id = "u1", url = "u1", name = "One!", addedAtMillis = 9)

        store.upsert(updated)

        assertEquals(listOf(updated), store.read())
    }

    @Test
    fun `remove reports whether the row existed`() {
        val store = store()
        store.upsert(StoredRepo(id = "u1", url = "u1", name = "One", addedAtMillis = 5))

        assertTrue(store.remove("u1"))
        assertFalse(store.remove("u1"))
        assertTrue(store.read().isEmpty())
    }

    @Test
    fun `corrupted file degrades to empty`() {
        val file = tmp.newFile()
        file.writeText("{ not json")
        val store = RepoStore(file)

        assertTrue(store.read().isEmpty())
    }
}

class BundledExtensionsInstallerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `missing bundled package is installed`() =
        runTest {
            val zip = TestPackages.packageExtension(tmp.newFolder())
            val engine = engine()
            val installer =
                BundledExtensionsInstaller(
                    bundled = listOf(BundledPackage("cloudimage.demo", "test-extension.zip", Sha256.of(zip))),
                    source = { fileName -> if (fileName == "test-extension.zip") zip.readBytes() else null },
                    stagingDir = tmp.newFolder(),
                )

            val written = installer.reconcile(engine)

            assertEquals(1, written)
            assertEquals(
                listOf("cloudimage.demo"),
                engine.installed.value
                    .orEmpty()
                    .map { it.id },
            )
        }

    @Test
    fun `up-to-date package is skipped`() =
        runTest {
            val zip = TestPackages.packageExtension(tmp.newFolder())
            val engine = engine()
            val installer =
                BundledExtensionsInstaller(
                    bundled = listOf(BundledPackage("cloudimage.demo", "test-extension.zip", Sha256.of(zip))),
                    source = { zip.readBytes() },
                    stagingDir = tmp.newFolder(),
                )
            installer.reconcile(engine)

            val second = installer.reconcile(engine)

            assertEquals(0, second)
        }

    @Test
    fun `app update with new checksum reinstalls`() =
        runTest {
            val engine = engine()
            val first = TestPackages.packageExtension(tmp.newFolder())
            BundledExtensionsInstaller(
                bundled = listOf(BundledPackage("cloudimage.demo", "test-extension.zip", Sha256.of(first))),
                source = { first.readBytes() },
                stagingDir = tmp.newFolder(),
            ).reconcile(engine)

            val updated = TestPackages.packageExtension(tmp.newFolder(), TestPackages.manifestJson(versionName = "1.1.0"))
            val secondPass =
                BundledExtensionsInstaller(
                    bundled = listOf(BundledPackage("cloudimage.demo", "test-extension.zip", Sha256.of(updated))),
                    source = { updated.readBytes() },
                    stagingDir = tmp.newFolder(),
                )

            val written = secondPass.reconcile(engine)

            assertEquals(1, written)
            assertEquals(
                "1.1.0",
                engine.installed.value
                    .orEmpty()
                    .single()
                    .manifest
                    ?.versionName,
            )
        }

    @Test
    fun `bundled entry with no payload is skipped`() =
        runTest {
            val engine = engine()
            val installer =
                BundledExtensionsInstaller(
                    bundled = listOf(BundledPackage("cloudimage.ghost", "ghost.zip", "00")),
                    source = { null },
                    stagingDir = tmp.newFolder(),
                )

            assertEquals(0, installer.reconcile(engine))
            assertTrue(
                engine.installed.value
                    .orEmpty()
                    .isEmpty(),
            )
        }

    private fun engine(): ExtensionRepository {
        val dirs = ExtensionDirs(tmp.newFolder()).ensure()
        val index = ExtensionIndex(dirs.indexFile)
        return DefaultExtensionRepository(
            installer = ExtensionInstaller(dirs, index),
            scanner = ExtensionScanner(dirs, index),
            loader =
                ExtensionLoader(
                    dirs,
                    UrlClassLoaderFactory(),
                    CloudimageProviderHttpClient(
                        com.cloudimage.core.network.CloudimageHttpClient(
                            okhttp3.OkHttpClient(),
                            kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
                        ),
                    ),
                    com.cloudimage.provider.api
                        .ProviderSettings { null },
                ),
            ioDispatcher = Dispatchers.Unconfined,
        )
    }
}
