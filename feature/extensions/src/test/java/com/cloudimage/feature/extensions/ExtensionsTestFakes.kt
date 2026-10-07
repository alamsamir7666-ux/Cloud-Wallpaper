package com.cloudimage.feature.extensions

import com.cloudimage.core.data.repository.SearchOutcome
import com.cloudimage.core.data.repository.SourceAlbum
import com.cloudimage.core.data.repository.SourceCategory
import com.cloudimage.core.data.repository.SourceInfo
import com.cloudimage.core.data.repository.SourceSection
import com.cloudimage.core.data.repository.WallpaperSources
import com.cloudimage.core.model.Page
import com.cloudimage.core.model.Wallpaper
import com.cloudimage.core.model.WallpaperDetails
import com.cloudimage.core.model.WallpaperQuery
import com.cloudimage.core.network.NetworkError
import com.cloudimage.core.network.NetworkResult
import com.cloudimage.extensions.core.AddRepoResult
import com.cloudimage.extensions.core.ExtensionError
import com.cloudimage.extensions.core.ExtensionManifest
import com.cloudimage.extensions.core.ExtensionRepository
import com.cloudimage.extensions.core.ExtensionStatus
import com.cloudimage.extensions.core.InstallResult
import com.cloudimage.extensions.core.InstalledExtension
import com.cloudimage.extensions.core.LoadResult
import com.cloudimage.extensions.core.RepoBundleEntry
import com.cloudimage.extensions.core.RepoError
import com.cloudimage.extensions.core.RepoIndexDto
import com.cloudimage.extensions.core.RepoIndexResult
import com.cloudimage.extensions.core.RepoManager
import com.cloudimage.extensions.core.RepoPackageEntry
import com.cloudimage.extensions.core.StoredRepo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * The fakes and row builders shared by the three extension ViewModel test
 * classes (v1.0.20 screen split) — verbatim ports of the originals from the
 * monolithic ExtensionsViewModelTest.
 */

class FakeExtensionRepository : ExtensionRepository {
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

class FakeRepoManager : RepoManager {
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

class FakeSources : WallpaperSources {
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

    override suspend fun details(wallpaper: Wallpaper): NetworkResult<WallpaperDetails> =
        NetworkResult.Failure(NetworkError.Source("not scripted"))

    override suspend fun categories(sourceId: String): NetworkResult<List<SourceCategory>> = NetworkResult.Success(emptyList())

    override suspend fun homeAlbums(sourceId: String): NetworkResult<List<SourceAlbum>> = NetworkResult.Success(emptyList())

    override suspend fun albums(
        sourceId: String,
        categoryId: String,
    ): NetworkResult<List<SourceAlbum>> = NetworkResult.Success(emptyList())

    override suspend fun albumWallpapers(
        sourceId: String,
        albumId: String,
    ): NetworkResult<List<Wallpaper>> = NetworkResult.Success(emptyList())

    override suspend fun searchAlbums(
        sourceId: String,
        query: String,
    ): NetworkResult<List<SourceAlbum>> = NetworkResult.Success(emptyList())
}

fun row(id: String = "cloudimage.demo") =
    InstalledExtension(
        id = id,
        fileName = "$id.zip",
        sha256 = "cafebabe",
        status = ExtensionStatus.READY,
        manifest = null,
    )

/** A row whose manifest is readable — the only kind the counts and update logic see. */
fun manifestRow(
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

fun catalogEntry(
    id: String,
    name: String = "",
    versionName: String = "1.0.0",
    versionCode: Int = 1,
    description: String = "",
    categories: List<String> = emptyList(),
) = RepoPackageEntry(
    id = id,
    fileName = "$id.zip",
    sha256 = "00",
    name = name,
    versionName = versionName,
    versionCode = versionCode,
    description = description,
    categories = categories,
)

fun bundleEntry(
    id: String,
    name: String = "",
    description: String = "",
    packageIds: List<String> = emptyList(),
) = RepoBundleEntry(
    id = id,
    name = name,
    description = description,
    packageIds = packageIds,
)

fun repo(id: String) = StoredRepo(id, "$id/index.json", id, 1L)
