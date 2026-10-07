package com.cloudimage.feature.extensions

import androidx.lifecycle.SavedStateHandle
import com.cloudimage.core.testing.MainDispatcherRule
import com.cloudimage.extensions.core.ExtensionManifest
import com.cloudimage.extensions.core.InstallResult
import com.cloudimage.extensions.core.RepoIndexDto
import com.cloudimage.extensions.core.RepoIndexResult
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The bundle detail ViewModel (v1.2.2): member resolution in bundle
 * order, missing-id flagging, selection pre-checks, the sequential bulk
 * install (continue-on-failure), single-member install / uninstall.
 */
class BundleDetailViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun savedStateHandleFor(
        repoId: String = "r1",
        bundleId: String = "pack",
    ): SavedStateHandle =
        SavedStateHandle(
            mapOf(
                ExtensionsDestination.repoArg to ExtensionsDestination.encodeRepoId(repoId),
                ExtensionsDestination.bundleArg to ExtensionsDestination.encodeRepoId(bundleId),
            ),
        )

    private fun viewModel(
        repoId: String = "r1",
        bundleId: String = "pack",
        engine: FakeExtensionRepository = FakeExtensionRepository(),
        repos: FakeRepoManager = FakeRepoManager().apply { this.repos = mutableListOf(repo(repoId)) },
    ): BundleDetailViewModel =
        BundleDetailViewModel(
            savedStateHandle = savedStateHandleFor(repoId, bundleId),
            repoManager = repos,
            repository = engine,
        )

    private fun installOkManifest(id: String = "cloudimage.unsplash") =
        InstallResult.Installed(
            ExtensionManifest(
                id = id,
                name = id.substringAfterLast('.').replaceFirstChar { it.uppercase() },
                versionName = "1.0.0",
                versionCode = 1,
                apiVersion = 1,
                entryClass = "com.example.$id",
            ),
            "00",
        )

    /** A repo whose index carries packages "a", "b", ... and the bundle "pack" over [bundleIds]. */
    private fun bundleCatalog(
        packages: List<String>,
        bundleIds: List<String> = packages,
        bundleId: String = "pack",
        bundleName: String = "Pack",
    ): FakeRepoManager =
        FakeRepoManager().apply {
            repos = mutableListOf(repo("r1"))
            catalogResult =
                RepoIndexResult.Ok(
                    RepoIndexDto(
                        name = "Official",
                        packages = packages.map { catalogEntry(it) },
                        bundles = listOf(bundleEntry(bundleId, name = bundleName, packageIds = bundleIds)),
                    ),
                )
        }

    @Test
    fun membersResolveInBundleOrderAndMissingIdsAreFlagged() =
        runTest {
            val repos = bundleCatalog(packages = listOf("b", "a"), bundleIds = listOf("a", "b", "ghost"))
            val viewModel = viewModel(repos = repos)

            val state = viewModel.state.first { it.status == BundleCatalogStatus.READY }
            assertEquals(listOf("a", "b"), state.entries.map { it.id })
            assertEquals(listOf("ghost"), state.missingIds)
            assertEquals(3, state.totalMembers)
        }

    @Test
    fun duplicatedPackageIdsCollapseToSingleMembers() =
        runTest {
            val repos = bundleCatalog(packages = listOf("a"), bundleIds = listOf("a", "a", "a"))
            val viewModel = viewModel(repos = repos)

            val state = viewModel.state.first { it.status == BundleCatalogStatus.READY }
            assertEquals(listOf("a"), state.entries.map { it.id })
            assertTrue(state.missingIds.isEmpty())
        }

    @Test
    fun selectionPreChecksExactlyTheUninstalledMembers() =
        runTest {
            val engine = FakeExtensionRepository()
            engine.state.value = listOf(manifestRow("a"))
            val repos = bundleCatalog(packages = listOf("a", "b", "c"))
            val viewModel = viewModel(engine = engine, repos = repos)

            val state = viewModel.state.first { s -> s.status == BundleCatalogStatus.READY && s.installed.isNotEmpty() }
            assertEquals(setOf("b", "c"), state.selected)
        }

    @Test
    fun toggleSelectedFlipsMembership() =
        runTest {
            val repos = bundleCatalog(packages = listOf("a", "b"))
            val viewModel = viewModel(repos = repos)
            viewModel.state.first { it.status == BundleCatalogStatus.READY }

            viewModel.toggleSelected("a")
            assertEquals(setOf("b"), viewModel.state.value.selected)

            viewModel.toggleSelected("a")
            assertEquals(setOf("a", "b"), viewModel.state.value.selected)
        }

    @Test
    fun installSelectedRunsEveryMemberSequentiallyAndClearsSelection() =
        runTest {
            val repos = bundleCatalog(packages = listOf("a", "b", "c")).apply { installResult = installOkManifest() }
            val viewModel = viewModel(repos = repos)
            viewModel.state.first { it.status == BundleCatalogStatus.READY }
            val events = mutableListOf<RepoDetailEvent>()
            val collector =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    viewModel.events.collect { events += it }
                }

            viewModel.installSelected()

            advanceUntilIdle()
            assertEquals(listOf("a", "b", "c"), repos.installedEntries.map { it.id })
            assertTrue(
                viewModel.state.value.installing
                    .isEmpty(),
            )
            assertTrue(
                viewModel.state.value.selected
                    .isEmpty(),
            )
            assertTrue(
                viewModel.state.value.failedInstalls
                    .isEmpty(),
            )
            assertEquals(listOf(RepoDetailEvent.BundleInstalled("Pack", 3, 0)), events)
            collector.cancel()
        }

    @Test
    fun installSelectedContinuesPastFailuresAndFlagsThem() =
        runTest {
            // The fake's default install result is a checksum failure.
            val repos = bundleCatalog(packages = listOf("a", "b"))
            val viewModel = viewModel(repos = repos)
            viewModel.state.first { it.status == BundleCatalogStatus.READY }
            val events = mutableListOf<RepoDetailEvent>()
            val collector =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    viewModel.events.collect { events += it }
                }

            viewModel.installSelected()

            advanceUntilIdle()
            assertEquals(listOf("a", "b"), repos.installedEntries.map { it.id })
            assertEquals(setOf("a", "b"), viewModel.state.value.failedInstalls)
            assertTrue(
                viewModel.state.value.selected
                    .isEmpty(),
            )
            assertEquals(listOf(RepoDetailEvent.BundleInstalled("Pack", 0, 2)), events)
            collector.cancel()
        }

    @Test
    fun installMemberInstallsOneRowAndDeselectsIt() =
        runTest {
            val repos = bundleCatalog(packages = listOf("a", "b")).apply { installResult = installOkManifest() }
            val viewModel = viewModel(repos = repos)
            viewModel.state.first { it.status == BundleCatalogStatus.READY }
            val events = mutableListOf<RepoDetailEvent>()
            val collector =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    viewModel.events.collect { events += it }
                }

            viewModel.installMember(catalogEntry("a"))

            advanceUntilIdle()
            assertEquals(listOf("a"), repos.installedEntries.map { it.id })
            assertEquals(setOf("b"), viewModel.state.value.selected)
            assertEquals(listOf(RepoDetailEvent.Installed("A")), events)
            collector.cancel()
        }

    @Test
    fun uninstallMemberForwardsTheIdAndEmitsUninstalled() =
        runTest {
            val engine = FakeExtensionRepository()
            engine.state.value = listOf(manifestRow("a"))
            val repos = bundleCatalog(packages = listOf("a", "b"))
            val viewModel = viewModel(engine = engine, repos = repos)
            viewModel.state.first { it.installed.isNotEmpty() }
            val events = mutableListOf<RepoDetailEvent>()
            val collector =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    viewModel.events.collect { events += it }
                }

            viewModel.uninstallMember(catalogEntry("a", name = "Alpha"))

            advanceUntilIdle()
            assertEquals(listOf("a"), engine.uninstalledIds)
            assertEquals(listOf(RepoDetailEvent.Uninstalled("Alpha")), events)
            collector.cancel()
        }

    @Test
    fun unknownBundleIdMarksTheCatalogMissing() =
        runTest {
            val repos = bundleCatalog(packages = listOf("a"), bundleId = "other")
            val viewModel = viewModel(bundleId = "pack", repos = repos)

            val state = viewModel.state.first { it.status == BundleCatalogStatus.MISSING }
            assertEquals(null, state.bundle)
            assertTrue(state.entries.isEmpty())
            assertTrue(state.missingIds.isEmpty())
        }

    @Test
    fun refreshReloadsTheCatalog() =
        runTest {
            val repos = bundleCatalog(packages = listOf("a"))
            val viewModel = viewModel(repos = repos)
            viewModel.state.first { it.status == BundleCatalogStatus.READY }
            assertEquals(1, repos.catalogCalls)

            viewModel.refresh()

            viewModel.state.first { repos.catalogCalls >= 2 }
            assertEquals(2, repos.catalogCalls)
        }
}
