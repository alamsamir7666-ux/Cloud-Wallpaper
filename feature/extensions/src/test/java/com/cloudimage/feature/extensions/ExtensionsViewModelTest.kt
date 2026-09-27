package com.cloudimage.feature.extensions

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.cloudimage.core.datastore.UserPreferencesRepository
import com.cloudimage.core.testing.MainDispatcherRule
import com.cloudimage.extensions.core.AddRepoResult
import com.cloudimage.extensions.core.RepoError
import com.cloudimage.extensions.core.RepoIndexDto
import com.cloudimage.extensions.core.RepoIndexResult
import com.cloudimage.extensions.core.RepoPackageEntry
import com.cloudimage.extensions.core.StoredRepo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The repository browser ViewModel (v1.0.20): repos in and out, catalogs
 * for the counts, and the three-population Downloaded / Disabled / Not
 * downloaded math the bottom bar renders.
 */
class ExtensionsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder.builder().assureDeletion().build()

    private fun TestScope.newPreferences() =
        UserPreferencesRepository(
            PreferenceDataStoreFactory.create(scope = backgroundScope) {
                tmpFolder.newFile("preferences_${System.nanoTime()}.preferences_pb")
            },
        )

    private fun TestScope.viewModel(
        engine: FakeExtensionRepository = FakeExtensionRepository(),
        repos: FakeRepoManager = FakeRepoManager(),
        preferences: UserPreferencesRepository = newPreferences(),
    ): ExtensionsViewModel =
        ExtensionsViewModel(
            repository = engine,
            repoManager = repos,
            preferences = preferences,
        )

    @Test
    fun initialRefreshHappensOnConstruction() =
        runTest {
            val fake = FakeExtensionRepository()

            viewModel(fake)

            assertTrue(fake.refreshed >= 1)
        }

    @Test
    fun stateReflectsInstalledRowsAndClearsLoading() =
        runTest {
            val fake = FakeExtensionRepository()
            val viewModel = viewModel(fake)
            assertTrue(viewModel.state.value.loading)

            fake.state.value = listOf(row())

            val state = viewModel.state.value
            assertFalse(state.loading)
            assertEquals(listOf(row()), state.installed)
        }

    @Test
    fun corruptedListingKeepsLoadingFalseAndStatuses() =
        runTest {
            val fake = FakeExtensionRepository()
            val viewModel = viewModel(fake)
            val corrupted = row().copy(status = com.cloudimage.extensions.core.ExtensionStatus.CORRUPTED)

            fake.state.value = listOf(corrupted)

            assertEquals(listOf(corrupted), viewModel.state.value.installed)
            assertFalse(viewModel.state.value.loading)
        }

    @Test
    fun refreshIsRetriggerable() =
        runTest {
            val fake = FakeExtensionRepository()
            val viewModel = viewModel(fake)
            val before = fake.refreshed

            viewModel.refresh()

            assertTrue(fake.refreshed > before)
        }

    @Test
    fun addRepoSuccessStoresItAndLoadsItsCatalog() =
        runTest {
            val repo =
                StoredRepo(
                    id = "https://example.com/repo/index.json",
                    url = "https://example.com/repo/index.json",
                    name = "Official",
                    addedAtMillis = 1L,
                )
            val repos =
                FakeRepoManager().apply {
                    addResult = AddRepoResult.Added(repo)
                    catalogResult =
                        RepoIndexResult.Ok(
                            RepoIndexDto(
                                name = "Official",
                                packages =
                                    listOf(
                                        RepoPackageEntry(
                                            id = "cloudimage.unsplash",
                                            fileName = "cloudimage.unsplash.zip",
                                            sha256 = "00",
                                        ),
                                    ),
                            ),
                        )
                }
            val viewModel = viewModel(repos = repos)

            viewModel.addRepo("https://example.com/repo")

            assertTrue("https://example.com/repo" in repos.addedUrls)
            val state = viewModel.state.value
            assertEquals(listOf("Official"), state.repos.map { it.name })
            assertEquals(
                listOf("cloudimage.unsplash"),
                state.catalogs.values
                    .filterNotNull()
                    .flatten()
                    .map { it.id },
            )
            assertFalse(state.addingRepo)
        }

    @Test
    fun addRepoFailureSurfacesErrorAndKeepsState() =
        runTest {
            val repos = FakeRepoManager().apply { addResult = AddRepoResult.Failed(RepoError.BadIndex("bad json")) }
            val viewModel = viewModel(repos = repos)

            viewModel.addRepo("https://example.com/repo")

            val state = viewModel.state.value
            assertTrue(state.repoError is RepoError.BadIndex)
            assertFalse(state.addingRepo)
            assertTrue(state.repos.isEmpty())
        }

    @Test
    fun removeRepoDropsItAndItsCatalog() =
        runTest {
            val repos =
                FakeRepoManager().apply {
                    repos = mutableListOf(repo("r1"), repo("r2"))
                }
            val viewModel = viewModel(repos = repos)
            advanceUntilIdle()
            assertTrue(
                viewModel.state.value.catalogs
                    .containsKey("r1"),
            )

            viewModel.removeRepo(repo("r1"))

            advanceUntilIdle()
            val state = viewModel.state.value
            assertEquals(listOf("r2"), state.repos.map { it.id })
            assertFalse(state.catalogs.containsKey("r1"))
            assertEquals(listOf("r1"), repos.removedIds)
        }

    @Test
    fun disablingASourceFeedsTheDisabledCount() =
        runTest {
            val preferences = newPreferences()
            val fake = FakeExtensionRepository()
            val viewModel = viewModel(fake, preferences = preferences)
            val wallhaven = manifestRow("cloudimage.wallhaven")
            fake.state.value = listOf(wallhaven)

            viewModel.state.first { it.installed.isNotEmpty() }
            preferences.setSourceEnabled("cloudimage.wallhaven", false)

            val state = viewModel.state.first { "cloudimage.wallhaven" in it.disabledSources }
            assertEquals(setOf("cloudimage.wallhaven"), state.disabledSources)
            assertEquals(1, state.disabledCount)
            assertEquals(0, state.downloadedCount)
        }

    // ---- The three-population counts (Cloudstream naming) ----

    @Test
    fun countsSplitDownloadedDisabledAndNotDownloaded() {
        val state =
            ExtensionsUiState(
                installed = listOf(manifestRow("cloudimage.wallhaven"), manifestRow("cloudimage.demo")),
                disabledSources = setOf("cloudimage.demo"),
                catalogs =
                    mapOf(
                        "r1" to listOf(catalogEntry("cloudimage.wallhaven"), catalogEntry("cloudimage.unsplash")),
                        "r2" to listOf(catalogEntry("cloudimage.wallhaven")),
                    ),
            )

        assertEquals(1, state.downloadedCount)
        assertEquals(1, state.disabledCount)
        // Unsplash once — wallhaven is installed and demo never appears in a catalog.
        assertEquals(1, state.notDownloadedCount)
    }

    @Test
    fun statsSegmentsDropEmptyPopulations() {
        // The v1.0.10 crash: one installed extension, nothing disabled,
        // nothing to download — the bar asked Compose for weight(0f),
        // which throws and took the screen down with it.
        val state = ExtensionsUiState(installed = listOf(manifestRow("cloudimage.wallhaven")))

        val segments = state.statsSegments()

        assertEquals(listOf(ExtensionStatSegment(ExtensionStatKind.DOWNLOADED, 1)), segments)
        segments.forEach { assertTrue(it.count > 0) }
    }

    @Test
    fun statsSegmentsAreEmptyWhenThereIsNothingToCount() {
        assertEquals(emptyList<ExtensionStatSegment>(), ExtensionsUiState().statsSegments())
    }

    @Test
    fun statsSegmentsCoverEveryNonZeroPopulation() {
        val state =
            ExtensionsUiState(
                installed = listOf(manifestRow("cloudimage.wallhaven"), manifestRow("cloudimage.demo")),
                disabledSources = setOf("cloudimage.demo"),
                catalogs = mapOf("r1" to listOf(catalogEntry("cloudimage.unsplash"))),
            )

        assertEquals(
            listOf(
                ExtensionStatSegment(ExtensionStatKind.DOWNLOADED, 1),
                ExtensionStatSegment(ExtensionStatKind.DISABLED, 1),
                ExtensionStatSegment(ExtensionStatKind.NOT_DOWNLOADED, 1),
            ),
            state.statsSegments(),
        )
    }
}
