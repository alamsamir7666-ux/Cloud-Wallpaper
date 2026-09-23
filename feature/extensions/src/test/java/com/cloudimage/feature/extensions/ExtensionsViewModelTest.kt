package com.cloudimage.feature.extensions

import com.cloudimage.core.testing.MainDispatcherRule
import com.cloudimage.extensions.core.ExtensionError
import com.cloudimage.extensions.core.ExtensionRepository
import com.cloudimage.extensions.core.ExtensionStatus
import com.cloudimage.extensions.core.InstallResult
import com.cloudimage.extensions.core.InstalledExtension
import com.cloudimage.extensions.core.LoadResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class ExtensionsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeExtensionRepository : ExtensionRepository {
        val state = MutableStateFlow<List<InstalledExtension>?>(null)
        var refreshed = 0
            private set
        val uninstalledIds = mutableListOf<String>()

        override val installed: StateFlow<List<InstalledExtension>?> = state.asStateFlow()

        override suspend fun refresh() {
            refreshed++
        }

        override suspend fun install(
            source: File,
            expectedSha256: String?,
        ): InstallResult = InstallResult.Failed(ExtensionError.InvalidManifest("not under test"))

        override suspend fun uninstall(extensionId: String): Boolean {
            uninstalledIds += extensionId
            return true
        }

        override suspend fun providerFor(extension: InstalledExtension): LoadResult =
            LoadResult.Failed(ExtensionError.NotLoadable(extension.status))
    }

    private fun row(id: String = "cloudimage.demo") =
        InstalledExtension(
            id = id,
            fileName = "$id.zip",
            sha256 = "cafebabe",
            status = ExtensionStatus.READY,
            manifest = null,
        )

    @Test
    fun initialRefreshHappensOnConstruction() =
        runTest {
            val fake = FakeExtensionRepository()

            ExtensionsViewModel(fake)

            assertTrue(fake.refreshed >= 1)
        }

    @Test
    fun stateReflectsInstalledRowsAndClearsLoading() =
        runTest {
            val fake = FakeExtensionRepository()
            val viewModel = ExtensionsViewModel(fake)
            assertTrue(viewModel.state.value.loading)

            fake.state.value = listOf(row())

            val state = viewModel.state.value
            assertFalse(state.loading)
            assertEquals(listOf(row()), state.extensions)
        }

    @Test
    fun corruptedListingKeepsLoadingFalseAndStatuses() =
        runTest {
            val fake = FakeExtensionRepository()
            val viewModel = ExtensionsViewModel(fake)
            val corrupted = row().copy(status = ExtensionStatus.CORRUPTED)

            fake.state.value = listOf(corrupted)

            assertEquals(listOf(corrupted), viewModel.state.value.extensions)
            assertFalse(viewModel.state.value.loading)
        }

    @Test
    fun uninstallForwardsIdToRepository() =
        runTest {
            val fake = FakeExtensionRepository()
            val viewModel = ExtensionsViewModel(fake)

            viewModel.uninstall(row())

            assertEquals(listOf("cloudimage.demo"), fake.uninstalledIds)
        }

    @Test
    fun refreshIsRetriggerable() =
        runTest {
            val fake = FakeExtensionRepository()
            val viewModel = ExtensionsViewModel(fake)
            val before = fake.refreshed

            viewModel.refresh()

            assertTrue(fake.refreshed > before)
        }
}
