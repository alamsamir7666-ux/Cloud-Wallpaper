package com.cloudimage.extensions.core

import com.cloudimage.core.network.CloudimageHttpClient
import com.cloudimage.core.network.NetworkError
import com.cloudimage.core.network.NetworkResult
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of adding a repository. */
sealed interface AddRepoResult {
    data class Added(
        val repo: StoredRepo,
    ) : AddRepoResult

    data class Failed(
        val error: RepoError,
    ) : AddRepoResult
}

/**
 * Add-repo-by-URL: fetch, parse, install.
 *
 * The default implementation is deliberately thin — parsing and
 * validation mirror what the engine already enforces (manifest
 * constraints, sha256, API gating) so a package that passes the index can
 * still be rejected at install time with a precise error. Downloaded
 * packages land in a cache dir first and are handed to
 * [ExtensionRepository] as opaque files, exactly like any other install
 * source.
 */
interface RepoManager {
    /**
     * Normalizes user input into an index URL: keeps a `.json` URL as-is,
     * otherwise appends `index.json` to the path. Adds no scheme magic —
     * garbage stays garbage and fails as [RepoError.InvalidUrl].
     */
    fun normalizeUrl(input: String): String?

    /** Fetches the index and persists the repo when it parses. */
    suspend fun add(inputUrl: String): AddRepoResult

    /** Removes a repo; its installed extensions are untouched. */
    fun remove(repoId: String): Boolean

    fun repos(): List<StoredRepo>

    /** Fetches and parses a repo's index on demand — no caching in V1. */
    suspend fun catalog(repo: StoredRepo): RepoIndexResult

    /**
     * Downloads one advertised package and installs it through the engine,
     * verifying the bytes against the index's sha256 promise.
     */
    suspend fun install(
        repo: StoredRepo,
        entry: RepoPackageEntry,
    ): InstallResult
}

@Singleton
class DefaultRepoManager
    @Inject
    constructor(
        private val client: CloudimageHttpClient,
        private val repository: ExtensionRepository,
        private val store: RepoStore,
        private val downloadsDir: File,
        private val nowMillis: () -> Long,
    ) : RepoManager {
        override fun normalizeUrl(input: String): String? {
            val trimmed = input.trim()
            if (trimmed.isEmpty() || trimmed.contains(' ')) {
                return null
            }
            if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                return null
            }
            return if (trimmed.substringAfterLast('/').endsWith(".json")) {
                trimmed
            } else if (trimmed.endsWith("/")) {
                trimmed + INDEX_FILE
            } else {
                "$trimmed/$INDEX_FILE"
            }
        }

        override suspend fun add(inputUrl: String): AddRepoResult {
            val url = normalizeUrl(inputUrl) ?: return AddRepoResult.Failed(RepoError.InvalidUrl)
            return when (val fetched = fetchIndex(url)) {
                is RepoIndexResult.Failed -> AddRepoResult.Failed(fetched.error)
                is RepoIndexResult.Ok -> {
                    val repo =
                        store.upsert(
                            StoredRepo(
                                id = url,
                                url = url,
                                name = fetched.index.name.ifBlank { url },
                                addedAtMillis = nowMillis(),
                            ),
                        )
                    AddRepoResult.Added(repo)
                }
            }
        }

        override fun remove(repoId: String): Boolean = store.remove(repoId)

        override fun repos(): List<StoredRepo> = store.read()

        /** Fetches and parses a repo's index on demand — no caching in V1. */
        override suspend fun catalog(repo: StoredRepo): RepoIndexResult = fetchIndex(repo.url)

        /**
         * Downloads one advertised package and installs it through the engine,
         * verifying the bytes against the index's sha256 promise.
         */
        override suspend fun install(
            repo: StoredRepo,
            entry: RepoPackageEntry,
        ): InstallResult {
            val url = "${repo.url.substringBeforeLast('/')}/${entry.fileName}"
            val bytes =
                when (val result = client.download(url)) {
                    is NetworkResult.Success -> result.value
                    is NetworkResult.Failure -> return InstallResult.Failed(entry.downloadFailed(result.error))
                }
            downloadsDir.mkdirs()
            val staging = File(downloadsDir, entry.fileName + "." + System.nanoTime() + ".download")
            return try {
                staging.writeBytes(bytes)
                repository.install(staging, expectedSha256 = entry.sha256)
            } catch (e: IOException) {
                InstallResult.Failed(ExtensionError.Io(e))
            } finally {
                staging.delete()
            }
        }

        private suspend fun fetchIndex(url: String): RepoIndexResult =
            when (val result = client.get(url)) {
                is NetworkResult.Failure -> RepoIndexResult.Failed(RepoError.Network(result.error))
                is NetworkResult.Success ->
                    try {
                        RepoIndexResult.Ok(json.decodeFromString(RepoIndexDto.serializer(), result.value))
                    } catch (e: SerializationException) {
                        RepoIndexResult.Failed(RepoError.BadIndex("not valid JSON: ${e.message}"))
                    } catch (e: IllegalArgumentException) {
                        RepoIndexResult.Failed(RepoError.BadIndex(e.message ?: "unreadable index"))
                    }
            }

        private fun RepoPackageEntry.downloadFailed(error: NetworkError): ExtensionError =
            when (error) {
                is NetworkError.Http ->
                    ExtensionError.Io(IOException("download of $fileName answered HTTP ${error.code}"))
                is NetworkError.Io -> ExtensionError.Io(error.cause)
                NetworkError.Timeout -> ExtensionError.Io(IOException("download of $fileName timed out"))
                is NetworkError.Serialization -> ExtensionError.Io(IOException("download of $fileName produced bad data"))
                // Repo downloads never involve a plugin; kept for exhaustiveness.
                is NetworkError.Source -> ExtensionError.Io(IOException("download of $fileName failed: ${error.reason}"))
            }

        private companion object {
            const val INDEX_FILE = "index.json"

            private val json = Json { ignoreUnknownKeys = true }
        }
    }

/** Outcome of fetching a repo index. */
sealed interface RepoIndexResult {
    data class Ok(
        val index: RepoIndexDto,
    ) : RepoIndexResult

    data class Failed(
        val error: RepoError,
    ) : RepoIndexResult
}
