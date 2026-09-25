package com.cloudimage.feature.extensions

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
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
            private set
        val addedUrls = mutableListOf<String>()
        val removedIds = mutableListOf<String>()
        val installedEntries = mutableListOf<RepoPackageEntry>()
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

        override suspend fun catalog(repo: StoredRepo): RepoIndexResult = catalogResult

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
        ): NetworkResult<Page> = NetworkResult.Success(Page.EMPTY)

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
}
