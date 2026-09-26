package com.cloudimage.feature.extensions

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.cloudimage.core.data.repository.SearchOutcome
import com.cloudimage.core.data.repository.SourceInfo
import com.cloudimage.core.data.repository.SourceSection
import com.cloudimage.core.data.repository.WallpaperSources
import com.cloudimage.core.datastore.UserPreferencesRepository
import com.cloudimage.core.model.Page
import com.cloudimage.core.model.WallpaperQuery
import com.cloudimage.core.network.NetworkResult
import com.cloudimage.core.testing.MainDispatcherRule
import com.cloudimage.extensions.core.AddRepoResult
import com.cloudimage.extensions.core.ExtensionError
import com.cloudimage.extensions.core.ExtensionManifest
import com.cloudimage.extensions.core.ExtensionRepository
import com.cloudimage.extensions.core.ExtensionStatus
import com.cloudimage.extensions.core.InstallResult
import com.cloudimage.extensions.core.InstalledExtension
import com.cloudimage.extensions.core.LoadResult
import com.cloudimage.extensions.core.RepoError
import com.cloudimage.extensions.core.RepoIndexDto
import com.cloudimage.extensions.core.RepoIndexResult
import com.cloudimage.extensions.core.RepoManager
import com.cloudimage.extensions.core.RepoPackageEntry
import com.cloudimage.extensions.core.StoredRepo
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ExtensionsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder.builder().assureDeletion().build()

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

    private class FakeRepoManager : RepoManager {
        var repos = mutableListOf<StoredRepo>()
        val addedUrls = mutableListOf<String>()
        val removedIds = mutableListOf<String>()
        val installedEntries = mutableListOf<RepoPackageEntry>()
        var catalogCalls = 0
            private set
        var addResult: AddRepoResult = AddRepoResult.Failed(RepoError.InvalidUrl)
        var catalogResult: RepoIndexResult = RepoIndexResult.Ok(RepoIndexDto(name = "Official"))
        var installResult: InstallResult =
            InstallResult.Failed(ExtensionError.ChecksumMismatch("00", "11"))

        override fun normalizeUrl(input: String): String? = input.takeIf { it.startsWith("https://") }

        override suspend fun add(inputUrl: String): AddRepoResult {
            addedUrls += inputUrl
            if (addResult is AddRepoResult.Added) {
                repos = (repos + (addResult as AddRepoResult.Added).repo).distinctBy { it.id }.toMutableList()
            }
            return addResult
        }

        override fun remove(repoId: String): Boolean {
            removedIds += repoId
            repos = repos.filterNot { it.id == repoId }.toMutableList()
            return true
        }

        override fun repos(): List<StoredRepo> = repos

        override suspend fun catalog(repo: StoredRepo): RepoIndexResult {
            catalogCalls++
            return catalogResult
        }

        override suspend fun install(
            repo: StoredRepo,
            entry: RepoPackageEntry,
        ): InstallResult {
            installedEntries += entry
            return installResult
        }
    }

    private class FakeSources : WallpaperSources {
        val sourcesState = MutableStateFlow<List<SourceInfo>?>(null)
        override val sources: StateFlow<List<SourceInfo>?> = sourcesState.asStateFlow()

        val failuresState = MutableStateFlow<Map<String, String>>(emptyMap())
        override val loadFailures: StateFlow<Map<String, String>> = failuresState.asStateFlow()

        override suspend fun refresh() {}

        override suspend fun search(
            query: WallpaperQuery,
            page: Int,
            sourceId: String?,
        ): NetworkResult<SearchOutcome> = NetworkResult.Success(SearchOutcome(Page.EMPTY))

        override suspend fun suggestTags(
            query: String,
            sourceId: String?,
        ): List<String> = emptyList()

        override suspend fun sections(sourceId: String?): NetworkResult<List<SourceSection>> = NetworkResult.Success(emptyList())
    }

    private fun row(id: String = "cloudimage.demo") =
        InstalledExtension(
            id = id,
            fileName = "$id.zip",
            sha256 = "cafebabe",
            status = ExtensionStatus.READY,
            manifest = null,
        )

    /** A row whose manifest is readable — the only kind the Part 3 stats and update logic count. */
    private fun manifestRow(
        id: String = "cloudimage.demo",
        versionName: String = "1.0.0",
        versionCode: Int = 1,
    ): InstalledExtension =
        InstalledExtension(
            id = id,
            fileName = "$id.zip",
            sha256 = "cafebabe",
            status = ExtensionStatus.READY,
            manifest =
                ExtensionManifest(
                    id = id,
                    name = id,
                    versionName = versionName,
                    versionCode = versionCode,
                    apiVersion = 1,
                    entryClass = "com.example.$id",
                ),
        )

    private fun catalogEntry(
        id: String,
        versionName: String = "1.0.0",
        versionCode: Int = 1,
    ) = RepoPackageEntry(
        id = id,
        fileName = "$id.zip",
        sha256 = "00",
        versionName = versionName,
        versionCode = versionCode,
    )

    private fun repo(id: String) = StoredRepo(id, "$id/index.json", id, 1L)

    private fun TestScope.newPreferences() =
        UserPreferencesRepository(
            PreferenceDataStoreFactory.create(scope = backgroundScope) {
                tmpFolder.newFile("preferences_${System.nanoTime()}.preferences_pb")
            },
        )

    private fun TestScope.viewModel(
        engine: FakeExtensionRepository = FakeExtensionRepository(),
        repos: FakeRepoManager = FakeRepoManager(),
        sources: FakeSources = FakeSources(),
        preferences: UserPreferencesRepository = newPreferences(),
    ): ExtensionsViewModel =
        ExtensionsViewModel(
            repository = engine,
            repoManager = repos,
            preferences = preferences,
            sources = sources,
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
            assertEquals(listOf(row()), state.extensions)
        }

    @Test
    fun corruptedListingKeepsLoadingFalseAndStatuses() =
        runTest {
            val fake = FakeExtensionRepository()
            val viewModel = viewModel(fake)
            val corrupted = row().copy(status = ExtensionStatus.CORRUPTED)

            fake.state.value = listOf(corrupted)

            assertEquals(listOf(corrupted), viewModel.state.value.extensions)
            assertFalse(viewModel.state.value.loading)
        }

    @Test
    fun uninstallForwardsIdToRepository() =
        runTest {
            val fake = FakeExtensionRepository()
            val viewModel = viewModel(fake)

            viewModel.uninstall(row())

            assertEquals(listOf("cloudimage.demo"), fake.uninstalledIds)
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
    fun installPackageSuccessClearsInstallingFlag() =
        runTest {
            val repos =
                FakeRepoManager().apply {
                    installResult =
                        InstallResult.Installed(
                            ExtensionManifest(
                                id = "cloudimage.unsplash",
                                name = "Unsplash",
                                versionName = "1.0.0",
                                versionCode = 1,
                                apiVersion = 1,
                                entryClass = "com.cloudimage.unsplash.UnsplashWallpaperProvider",
                            ),
                            "00",
                        )
                }
            val viewModel = viewModel(repos = repos)
            val repo = StoredRepo("u", "https://example.com/repo/index.json", "Official", 1L)
            val entry = RepoPackageEntry(id = "cloudimage.unsplash", fileName = "cloudimage.unsplash.zip", sha256 = "00")

            viewModel.installPackage(repo, entry)

            assertEquals(listOf(entry), repos.installedEntries)
            assertTrue(
                viewModel.state.value.installing
                    .isEmpty(),
            )
            assertTrue(
                viewModel.state.value.failed
                    .isEmpty(),
            )
        }

    @Test
    fun installPackageFailureFlagsTheEntry() =
        runTest {
            val viewModel = viewModel()
            val repo = StoredRepo("u", "https://example.com/repo/index.json", "Official", 1L)
            val entry = RepoPackageEntry(id = "cloudimage.unsplash", fileName = "cloudimage.unsplash.zip", sha256 = "00")

            viewModel.installPackage(repo, entry)

            assertEquals("cloudimage.unsplash" in viewModel.state.value.failed, true)
            assertTrue(
                viewModel.state.value.installing
                    .isEmpty(),
            )
        }

    @Test
    fun savedKeysSurfaceInState() =
        runTest {
            val preferences = newPreferences()
            val viewModel = viewModel(preferences = preferences)

            viewModel.saveApiKey("cloudimage.unsplash", "secret")

            val state = viewModel.state.first { "cloudimage.unsplash" in it.keyedProviders }
            assertTrue("cloudimage.unsplash" in state.keyedProviders)
        }

    @Test
    fun sourcesFlowFeedsStateForKeyDialogs() =
        runTest {
            val sources = FakeSources()
            val viewModel = viewModel(sources = sources)

            sources.sourcesState.value =
                listOf(SourceInfo("cloudimage.unsplash", "Unsplash", requiresApiKey = true))

            assertEquals(
                listOf(SourceInfo("cloudimage.unsplash", "Unsplash", true)),
                viewModel.state.value.sources,
            )
        }

    @Test
    fun loadFailuresFeedStateForDiagnostics() =
        runTest {
            val sources = FakeSources()
            val viewModel = viewModel(sources = sources)

            sources.failuresState.value =
                mapOf("cloudimage.broken" to "entry class com.example.Broken is missing from the package")

            val state = viewModel.state.first { it.loadFailures.isNotEmpty() }

            assertEquals(
                "entry class com.example.Broken is missing from the package",
                state.loadFailures["cloudimage.broken"],
            )
        }

    // ---- Per-source enable/disable, stats, search and updates (v1.0.9 Part 3) ----

    @Test
    fun togglingASourcePersistsThroughPreferences() =
        runTest {
            val preferences = newPreferences()
            val fake = FakeExtensionRepository()
            val viewModel = viewModel(fake, preferences = preferences)
            val wallhaven = manifestRow("cloudimage.wallhaven")
            fake.state.value = listOf(wallhaven)

            viewModel.setSourceEnabled(wallhaven, enabled = false)

            val state = viewModel.state.first { "cloudimage.wallhaven" in it.disabledSources }
            assertEquals(setOf("cloudimage.wallhaven"), state.disabledSources)
            assertEquals(1, state.disabledCount)
            assertEquals(0, state.enabledCount)
        }

    @Test
    fun disablingThePinnedSourceClearsTheBrowsePin() =
        runTest {
            val preferences = newPreferences()
            preferences.setBrowseSourceId("cloudimage.wallhaven")
            val fake = FakeExtensionRepository()
            val viewModel = viewModel(fake, preferences = preferences)
            val wallhaven = manifestRow("cloudimage.wallhaven")
            fake.state.value = listOf(wallhaven)

            viewModel.setSourceEnabled(wallhaven, enabled = false)

            viewModel.state.first { "cloudimage.wallhaven" in it.disabledSources }
            assertEquals("", preferences.preferences.first().browseSourceId)
        }

    @Test
    fun disablingAnUnpinnedSourceKeepsThePin() =
        runTest {
            val preferences = newPreferences()
            preferences.setBrowseSourceId("cloudimage.other")
            val fake = FakeExtensionRepository()
            val viewModel = viewModel(fake, preferences = preferences)
            val wallhaven = manifestRow("cloudimage.wallhaven")
            fake.state.value = listOf(wallhaven)

            viewModel.setSourceEnabled(wallhaven, enabled = false)

            viewModel.state.first { "cloudimage.wallhaven" in it.disabledSources }
            assertEquals("cloudimage.other", preferences.preferences.first().browseSourceId)
        }

    @Test
    fun togglingRowsWithoutManifestIsIgnored() =
        runTest {
            val preferences = newPreferences()
            val viewModel = viewModel(preferences = preferences)
            val broken = row("cloudimage.broken") // manifest == null

            viewModel.setSourceEnabled(broken, enabled = false)

            advanceUntilIdle()
            val disabled = viewModel.state.value.disabledSources
            assertTrue(disabled.isEmpty())
        }

    @Test
    fun catalogSearchMatchesIdsCaseInsensitively() {
        val state = ExtensionsUiState(query = "UNSPLASH")

        assertTrue(state.catalogMatches(catalogEntry("cloudimage.unsplash")))
        assertFalse(state.catalogMatches(catalogEntry("cloudimage.wallhaven")))
    }

    @Test
    fun blankCatalogQueryShowsEverything() {
        val state = ExtensionsUiState(query = "")

        assertTrue(state.catalogMatches(catalogEntry("cloudimage.unsplash")))
        assertTrue(state.catalogMatches(catalogEntry("cloudimage.wallhaven")))
    }

    @Test
    fun setQueryIsPlainState() =
        runTest {
            val viewModel = viewModel()

            viewModel.setQuery("unsplash")

            assertEquals("unsplash", viewModel.state.value.query)
        }

    @Test
    fun updateAvailableComparesCodeThenName() {
        val state = ExtensionsUiState(extensions = listOf(manifestRow("cloudimage.demo", versionName = "1.0.0", versionCode = 1)))

        assertTrue(state.updateAvailable(catalogEntry("cloudimage.demo", versionName = "1.1.0", versionCode = 2)))
        assertTrue(state.updateAvailable(catalogEntry("cloudimage.demo", versionName = "1.0.1", versionCode = 1)))
        assertFalse(state.updateAvailable(catalogEntry("cloudimage.demo", versionName = "1.0.0", versionCode = 1)))
        assertFalse(state.updateAvailable(catalogEntry("cloudimage.demo", versionName = "0.9.0", versionCode = 0)))
        assertFalse(state.updateAvailable(catalogEntry("cloudimage.other", versionName = "9.9.9", versionCode = 9)))
    }

    @Test
    fun statsBarCountsEnabledDisabledAndAvailable() {
        val state =
            ExtensionsUiState(
                extensions = listOf(manifestRow("cloudimage.wallhaven"), manifestRow("cloudimage.demo")),
                disabledSources = setOf("cloudimage.demo"),
                catalogs =
                    mapOf(
                        "r1" to listOf(catalogEntry("cloudimage.wallhaven"), catalogEntry("cloudimage.unsplash")),
                        "r2" to listOf(catalogEntry("cloudimage.wallhaven")),
                    ),
            )

        assertEquals(1, state.enabledCount)
        assertEquals(1, state.disabledCount)
        // Unsplash once — wallhaven is installed and demo never appears in a catalog.
        assertEquals(1, state.availableCount)
    }

    @Test
    fun statsSegmentsDropEmptyPopulations() {
        // The v1.0.9 crash: one installed extension, nothing disabled,
        // nothing available — the bar asked Compose for weight(0f),
        // which throws and took the screen down with it.
        val state = ExtensionsUiState(extensions = listOf(manifestRow("cloudimage.wallhaven")))

        val segments = state.statsSegments()

        assertEquals(listOf(ExtensionStatSegment(ExtensionStatKind.ENABLED, 1)), segments)
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
                extensions = listOf(manifestRow("cloudimage.wallhaven"), manifestRow("cloudimage.demo")),
                disabledSources = setOf("cloudimage.demo"),
                catalogs = mapOf("r1" to listOf(catalogEntry("cloudimage.unsplash"))),
            )

        assertEquals(
            listOf(
                ExtensionStatSegment(ExtensionStatKind.ENABLED, 1),
                ExtensionStatSegment(ExtensionStatKind.DISABLED, 1),
                ExtensionStatSegment(ExtensionStatKind.AVAILABLE, 1),
            ),
            state.statsSegments(),
        )
    }

    @Test
    fun installOverAnInstalledEntryEmitsUpdated() =
        runTest {
            val repos =
                FakeRepoManager().apply {
                    installResult =
                        InstallResult.Installed(
                            ExtensionManifest(
                                id = "cloudimage.unsplash",
                                name = "Unsplash",
                                versionName = "1.1.0",
                                versionCode = 2,
                                apiVersion = 1,
                                entryClass = "com.cloudimage.unsplash.UnsplashWallpaperProvider",
                            ),
                            "00",
                        )
                }
            val fake = FakeExtensionRepository()
            val viewModel = viewModel(fake, repos = repos)
            fake.state.value = listOf(manifestRow("cloudimage.unsplash", versionName = "1.0.0", versionCode = 1))
            viewModel.state.first { it.extensions.isNotEmpty() }
            val events = mutableListOf<ExtensionsEvent>()
            // UNDISPATCHED: subscribe before installPackage's Main.immediate
            // coroutine can emit — a replay-less SharedFlow keeps nothing.
            val collector =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    viewModel.events.collect { events += it }
                }

            viewModel.installPackage(repo("r1"), catalogEntry("cloudimage.unsplash", versionName = "1.1.0", versionCode = 2))

            advanceUntilIdle()
            assertEquals(listOf<ExtensionsEvent>(ExtensionsEvent.Updated("cloudimage.unsplash")), events)
            collector.cancel()
        }

    @Test
    fun freshInstallsStillEmitInstalled() =
        runTest {
            val repos =
                FakeRepoManager().apply {
                    installResult =
                        InstallResult.Installed(
                            ExtensionManifest(
                                id = "cloudimage.unsplash",
                                name = "Unsplash",
                                versionName = "1.0.0",
                                versionCode = 1,
                                apiVersion = 1,
                                entryClass = "com.cloudimage.unsplash.UnsplashWallpaperProvider",
                            ),
                            "00",
                        )
                }
            val viewModel = viewModel(repos = repos)
            val events = mutableListOf<ExtensionsEvent>()
            val collector =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    viewModel.events.collect { events += it }
                }

            viewModel.installPackage(repo("r1"), catalogEntry("cloudimage.unsplash"))

            advanceUntilIdle()
            assertEquals(listOf<ExtensionsEvent>(ExtensionsEvent.Installed("cloudimage.unsplash")), events)
            collector.cancel()
        }

    @Test
    fun refreshRepoRefetchesThatCatalog() =
        runTest {
            val repos = FakeRepoManager()
            repos.repos = mutableListOf(repo("r1"), repo("r2"))
            val viewModel = viewModel(repos = repos)

            advanceUntilIdle()
            assertEquals(2, repos.catalogCalls)

            viewModel.refreshRepo(repo("r1"))

            advanceUntilIdle()
            assertEquals(3, repos.catalogCalls)
        }

    @Test
    fun refreshRepoWhilePendingIsIgnored() =
        runTest {
            val repos = FakeRepoManager()
            val viewModel = viewModel(repos = repos)
            advanceUntilIdle()
            assertEquals(0, repos.catalogCalls)

            // A repo whose catalog has never been fetched reads as pending;
            // the guard must swallow the duplicate fetch instead of queueing it.
            viewModel.refreshRepo(repo("ghost"))

            advanceUntilIdle()
            assertEquals(0, repos.catalogCalls)
        }
}
