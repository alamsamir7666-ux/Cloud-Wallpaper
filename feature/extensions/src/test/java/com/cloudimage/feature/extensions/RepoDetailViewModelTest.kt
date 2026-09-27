package com.cloudimage.feature.extensions

import androidx.lifecycle.SavedStateHandle
import com.cloudimage.core.testing.MainDispatcherRule
import com.cloudimage.extensions.core.ExtensionManifest
import com.cloudimage.extensions.core.InstallResult
import com.cloudimage.extensions.core.RepoError
import com.cloudimage.extensions.core.RepoIndexDto
import com.cloudimage.extensions.core.RepoIndexResult
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The repo detail ViewModel (v1.0.20): catalog fetch + retry, category
 * and search filtering, update detection, per-entry install / uninstall.
 */
class RepoDetailViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun savedStateHandleFor(repoId: String): SavedStateHandle =
        SavedStateHandle(mapOf(ExtensionsDestination.repoArg to ExtensionsDestination.encodeRepoId(repoId)))

    private fun viewModel(
        repoId: String = "r1",
        engine: FakeExtensionRepository = FakeExtensionRepository(),
        repos: FakeRepoManager = FakeRepoManager().apply { this.repos = mutableListOf(repo(repoId)) },
    ): RepoDetailViewModel =
        RepoDetailViewModel(
            savedStateHandle = savedStateHandleFor(repoId),
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

    @Test
    fun catalogLoadsEntriesAndResolvesTheRepo() =
        runTest {
            val repos =
                FakeRepoManager().apply {
                    repos = mutableListOf(repo("r1"))
                    catalogResult =
                        RepoIndexResult.Ok(
                            RepoIndexDto(
                                name = "Official",
                                packages = listOf(catalogEntry("cloudimage.unsplash")),
                            ),
                        )
                }

            val viewModel = viewModel(repos = repos)

            val state = viewModel.state.first { it.catalogStatus == RepoCatalogStatus.READY }
            assertEquals("r1", state.repo?.id)
            assertEquals(listOf("cloudimage.unsplash"), state.entries.map { it.id })
        }

    @Test
    fun unknownRepoIdMarksTheCatalogFailed() =
        runTest {
            val viewModel = viewModel(repoId = "ghost", repos = FakeRepoManager())

            val state = viewModel.state.first { it.catalogStatus == RepoCatalogStatus.FAILED }
            assertEquals(null, state.repo)
        }

    @Test
    fun catalogFailureIsRetryableThroughRefresh() =
        runTest {
            val repos =
                FakeRepoManager().apply {
                    repos = mutableListOf(repo("r1"))
                    catalogResult = RepoIndexResult.Failed(RepoError.BadIndex("bad json"))
                }
            val viewModel = viewModel(repos = repos)
            viewModel.state.first { it.catalogStatus == RepoCatalogStatus.FAILED }

            repos.catalogResult =
                RepoIndexResult.Ok(
                    RepoIndexDto(name = "Official", packages = listOf(catalogEntry("cloudimage.unsplash"))),
                )
            viewModel.refresh()

            val state = viewModel.state.first { it.catalogStatus == RepoCatalogStatus.READY }
            assertEquals(listOf("cloudimage.unsplash"), state.entries.map { it.id })
        }

    @Test
    fun categoriesUnionIsDistinctAndSorted() {
        val state =
            RepoDetailUiState(
                entries =
                    listOf(
                        catalogEntry("cloudimage.unsplash", categories = listOf("nature", "photography")),
                        catalogEntry("cloudimage.wallhaven", categories = listOf("anime")),
                        catalogEntry("cloudimage.pexels", categories = listOf("photography")),
                    ),
            )

        assertEquals(listOf("anime", "nature", "photography"), state.categories)
    }

    @Test
    fun categoryFilterSelectsMatchingEntries() {
        val state =
            RepoDetailUiState(
                entries =
                    listOf(
                        catalogEntry("cloudimage.unsplash", categories = listOf("photography")),
                        catalogEntry("cloudimage.wallhaven", categories = listOf("anime")),
                    ),
                category = "photography",
            )

        assertEquals(listOf("cloudimage.unsplash"), state.visibleEntries().map { it.id })
    }

    @Test
    fun nullCategoryShowsEverything() {
        val state =
            RepoDetailUiState(
                entries =
                    listOf(
                        catalogEntry("cloudimage.unsplash", categories = listOf("photography")),
                        catalogEntry("cloudimage.wallhaven", categories = listOf("anime")),
                    ),
                category = null,
            )

        assertEquals(2, state.visibleEntries().size)
    }

    @Test
    fun queryMatchesIdNameAndDescriptionCaseInsensitively() {
        val entries =
            listOf(
                catalogEntry("cloudimage.unsplash", name = "Unsplash", description = "photography"),
                catalogEntry("cloudimage.wallhaven", name = "Wallhaven", description = "anime wallpapers"),
                catalogEntry("cloudimage.pexels", name = "Pexels"),
            )
        val byId = RepoDetailUiState(entries = entries, query = "UNSPLASH")
        val byName = RepoDetailUiState(entries = entries, query = "wallhaven")
        val byDescription = RepoDetailUiState(entries = entries, query = "photography")

        assertEquals(listOf("cloudimage.unsplash"), byId.visibleEntries().map { it.id })
        assertEquals(listOf("cloudimage.wallhaven"), byName.visibleEntries().map { it.id })
        assertEquals(listOf("cloudimage.unsplash"), byDescription.visibleEntries().map { it.id })
    }

    @Test
    fun visibleEntriesSortByDisplayName() {
        val state =
            RepoDetailUiState(
                entries =
                    listOf(
                        catalogEntry("cloudimage.pexels", name = "Pexels"),
                        catalogEntry("cloudimage.wallhaven", name = "Wallhaven"),
                        catalogEntry("cloudimage.unsplash", name = "Unsplash"),
                    ),
            )

        assertEquals(
            listOf("Pexels", "Unsplash", "Wallhaven"),
            state.visibleEntries().map { displayName(it) },
        )
    }

    @Test
    fun displayNameFallsBackToTheIdSegment() {
        assertEquals("Wallhaven", displayName(catalogEntry("cloudimage.wallhaven")))
        assertEquals("Wallhaven", displayName(catalogEntry("cloudimage.wallhaven", name = "Wallhaven")))
    }

    @Test
    fun updateAvailableComparesCodeThenName() {
        val state = RepoDetailUiState(installed = listOf(manifestRow("cloudimage.demo", versionName = "1.0.0", versionCode = 1)))

        assertTrue(state.updateAvailable(catalogEntry("cloudimage.demo", versionName = "1.1.0", versionCode = 2)))
        assertTrue(state.updateAvailable(catalogEntry("cloudimage.demo", versionName = "1.0.1", versionCode = 1)))
        assertFalse(state.updateAvailable(catalogEntry("cloudimage.demo", versionName = "1.0.0", versionCode = 1)))
        assertFalse(state.updateAvailable(catalogEntry("cloudimage.demo", versionName = "0.9.0", versionCode = 0)))
        assertFalse(state.updateAvailable(catalogEntry("cloudimage.other", versionName = "9.9.9", versionCode = 9)))
    }

    @Test
    fun installPackageSuccessClearsInstallingFlagAndEmitsInstalled() =
        runTest {
            val repos =
                FakeRepoManager().apply {
                    this.repos = mutableListOf(repo("r1"))
                    installResult = installOkManifest()
                }
            val viewModel = viewModel(repos = repos)
            val entry = catalogEntry("cloudimage.unsplash", name = "Unsplash")
            val events = mutableListOf<RepoDetailEvent>()
            val collector =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    viewModel.events.collect { events += it }
                }

            viewModel.installPackage(entry)

            advanceUntilIdle()
            assertEquals(listOf(entry), repos.installedEntries)
            assertTrue(
                viewModel.state.value.installing
                    .isEmpty(),
            )
            assertTrue(
                viewModel.state.value.failedInstalls
                    .isEmpty(),
            )
            assertEquals(listOf<RepoDetailEvent>(RepoDetailEvent.Installed("Unsplash")), events)
            collector.cancel()
        }

    @Test
    fun installOverAnInstalledEntryEmitsUpdated() =
        runTest {
            val repos =
                FakeRepoManager().apply {
                    this.repos = mutableListOf(repo("r1"))
                    installResult = installOkManifest()
                }
            val fake = FakeExtensionRepository()
            val viewModel = viewModel(engine = fake, repos = repos)
            fake.state.value = listOf(manifestRow("cloudimage.unsplash", versionName = "1.0.0", versionCode = 1))
            viewModel.state.first { it.installed.isNotEmpty() }
            val events = mutableListOf<RepoDetailEvent>()
            val collector =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    viewModel.events.collect { events += it }
                }

            viewModel.installPackage(catalogEntry("cloudimage.unsplash", name = "Unsplash", versionName = "1.1.0", versionCode = 2))

            advanceUntilIdle()
            assertEquals(listOf<RepoDetailEvent>(RepoDetailEvent.Updated("Unsplash")), events)
            collector.cancel()
        }

    @Test
    fun installPackageFailureFlagsTheEntry() =
        runTest {
            val repos =
                FakeRepoManager().apply {
                    this.repos = mutableListOf(repo("r1"))
                } // default installResult is a failure
            val viewModel = viewModel(repos = repos)

            viewModel.installPackage(catalogEntry("cloudimage.unsplash"))

            advanceUntilIdle()
            assertTrue("cloudimage.unsplash" in viewModel.state.value.failedInstalls)
            assertTrue(
                viewModel.state.value.installing
                    .isEmpty(),
            )
        }

    @Test
    fun uninstallPackageForwardsTheIdAndEmitsUninstalled() =
        runTest {
            val fake = FakeExtensionRepository()
            val viewModel = viewModel(engine = fake)
            fake.state.value = listOf(manifestRow("cloudimage.unsplash"))
            viewModel.state.first { it.installed.isNotEmpty() }
            val events = mutableListOf<RepoDetailEvent>()
            val collector =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    viewModel.events.collect { events += it }
                }

            viewModel.uninstallPackage(catalogEntry("cloudimage.unsplash", name = "Unsplash"))

            advanceUntilIdle()
            assertEquals(listOf("cloudimage.unsplash"), fake.uninstalledIds)
            assertEquals(listOf<RepoDetailEvent>(RepoDetailEvent.Uninstalled("Unsplash")), events)
            collector.cancel()
        }

    @Test
    fun setCategoryLowerCasesAndQueryIsPlainState() =
        runTest {
            val viewModel = viewModel()

            viewModel.setCategory("Anime")
            assertEquals("anime", viewModel.state.value.category)

            viewModel.setQuery("unsplash")
            assertEquals("unsplash", viewModel.state.value.query)
        }

    @Test
    fun refreshRefetchesTheCatalog() =
        runTest {
            val repos = FakeRepoManager().apply { repos = mutableListOf(repo("r1")) }
            val viewModel = viewModel(repos = repos)

            viewModel.state.first { it.catalogStatus != RepoCatalogStatus.LOADING }
            assertEquals(1, repos.catalogCalls)

            viewModel.refresh()

            viewModel.state.first { repos.catalogCalls >= 2 }
            assertEquals(2, repos.catalogCalls)
        }
}
