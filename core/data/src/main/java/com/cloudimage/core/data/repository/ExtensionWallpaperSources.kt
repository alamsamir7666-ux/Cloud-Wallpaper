package com.cloudimage.core.data.repository

import com.cloudimage.core.model.ContentRating
import com.cloudimage.core.model.Page
import com.cloudimage.core.model.Wallpaper
import com.cloudimage.core.model.WallpaperCategory
import com.cloudimage.core.model.WallpaperQuery
import com.cloudimage.core.model.WallpaperSorting
import com.cloudimage.core.network.NetworkError
import com.cloudimage.core.network.NetworkResult
import com.cloudimage.extensions.core.ExtensionRepository
import com.cloudimage.extensions.core.ExtensionStatus
import com.cloudimage.extensions.core.LoadResult
import com.cloudimage.extensions.core.ProviderTransportException
import com.cloudimage.extensions.core.reason
import com.cloudimage.provider.api.Capability
import com.cloudimage.provider.api.Filters
import com.cloudimage.provider.api.HomeSection
import com.cloudimage.provider.api.ProviderHttpException
import com.cloudimage.provider.api.WallpaperProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import javax.inject.Inject
import javax.inject.Singleton
import com.cloudimage.provider.api.ContentRating as ProviderRating
import com.cloudimage.provider.api.Page as ProviderPage
import com.cloudimage.provider.api.Wallpaper as ProviderWallpaper

/**
 * Default [WallpaperSources] over the extension engine.
 *
 * The engine's provider cache makes per-search loading cheap: a READY
 * extension that was loaded before is served from memory, keyed by the
 * package checksum. Sources that fail to load are skipped from the feed —
 * but never silently: [loadFailures] carries the reason so the
 * extension manager can tell the user exactly what is broken.
 */
@Singleton
class ExtensionWallpaperSources
    @Inject
    constructor(
        private val extensions: ExtensionRepository,
        appScope: CoroutineScope,
    ) : WallpaperSources {
        private val state = MutableStateFlow<List<SourceInfo>?>(null)
        override val sources: StateFlow<List<SourceInfo>?> = state.asStateFlow()

        private val failures = MutableStateFlow<Map<String, String>>(emptyMap())
        override val loadFailures: StateFlow<Map<String, String>> = failures.asStateFlow()

        init {
            appScope.launch { refresh() }
        }

        override suspend fun refresh() {
            extensions.refresh()
            val newFailures = mutableMapOf<String, String>()
            state.value =
                (extensions.installed.value.orEmpty())
                    .filter { it.status == ExtensionStatus.READY }
                    .mapNotNull { extension ->
                        val manifest = extension.manifest ?: return@mapNotNull null
                        when (val loaded = extensions.providerFor(extension)) {
                            is LoadResult.Loaded ->
                                SourceInfo(
                                    id = manifest.id,
                                    name =
                                        loaded.provider.meta.name
                                            .ifBlank { manifest.name },
                                    requiresApiKey = loaded.provider.meta.requiresApiKey,
                                )
                            is LoadResult.Failed -> {
                                newFailures[manifest.id] = loaded.error.reason
                                null
                            }
                        }
                    }
            failures.value = newFailures
        }

        override suspend fun search(
            query: WallpaperQuery,
            page: Int,
            sourceId: String?,
        ): NetworkResult<SearchOutcome> {
            val providers = readyProviders()
            // A pinned source routes the whole query to that one provider.
            val routed =
                sourceId
                    ?.let { id -> providers.filter { it.first == id } }
                    ?: providers
            if (routed.isEmpty()) {
                if (sourceId != null) {
                    // The pin survived in DataStore but the package did not
                    // survive on disk — say so instead of pretending the
                    // whole pipeline is offline.
                    return NetworkResult.Failure(
                        NetworkError.Source("source '$sourceId' is not installed or not usable — see the Extensions tab"),
                    )
                }
                // Not a connectivity problem — say so. Silently mapping this
                // to Io is exactly what made v1.0.0 tell users to "check
                // your connection" while their internet was fine.
                return NetworkResult.Failure(NetworkError.Source(noSourcesReason()))
            }
            val filters = query.toFilters()
            val ratings = query.contentRatings

            return coroutineScope {
                val results =
                    routed
                        .map { (_, provider) -> async { provider.dispatch(query, page, filters) } }
                        .awaitAll()

                val pages = results.mapNotNull { it.getOrNull() }
                val sourceFailures =
                    results
                        .zip(routed) { result, (id, provider) ->
                            result.exceptionOrNull()?.let { failure ->
                                SourceFailure(
                                    sourceId = id,
                                    sourceName = provider.meta.name.ifBlank { id.substringAfterLast('.') },
                                    error = failure.toNetworkError(),
                                )
                            }
                        }.mapNotNull { it }

                if (pages.isEmpty() && sourceFailures.isNotEmpty()) {
                    NetworkResult.Failure(sourceFailures.first().error)
                } else {
                    NetworkResult.Success(
                        SearchOutcome(
                            page =
                                Page(
                                    wallpapers =
                                        pages
                                            .flatMap { it.wallpapers }
                                            .filter { it.contentRating.toCore() in ratings }
                                            .map { it.toCore() },
                                    nextPage = pages.mapNotNull { it.nextPage }.maxOrNull(),
                                ),
                            // Partial failures ride along: a merged search keeps
                            // its surviving results and says exactly who failed.
                            sourceFailures = sourceFailures,
                        ),
                    )
                }
            }
        }

        /**
         * Suggestions only ever come from TAGS-declaring sources in scope —
         * never a third-party suggest service. Failures degrade to nothing:
         * the suggestion panel is guidance, not a promise.
         */
        override suspend fun suggestTags(
            query: String,
            sourceId: String?,
        ): List<String> {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return emptyList()
            val providers = readyProviders()
            val routed =
                sourceId
                    ?.let { id -> providers.filter { it.first == id } }
                    ?: providers
            return coroutineScope {
                routed
                    .filter { (_, provider) -> Capability.TAGS in provider.capabilities }
                    .map { (_, provider) -> async { runCatching { provider.suggestTags(trimmed) }.getOrNull() } }
                    .awaitAll()
                    .mapNotNull { it?.getOrNull() }
                    .flatten()
                    .distinctBy { it.lowercase() }
                    .take(TAG_SUGGESTION_LIMIT)
            }
        }

        /**
         * Blank text goes to the popular feed, non-blank to search —
         * sources without a popular capability always receive search
         * (they are expected to handle a blank query themselves).
         */
        private suspend fun WallpaperProvider.dispatch(
            query: WallpaperQuery,
            page: Int,
            filters: Filters,
        ): Result<ProviderPage> =
            if (query.text.isBlank() && Capability.POPULAR in capabilities) {
                popular(page, filters)
            } else {
                search(query.text, page, filters)
            }

        /**
         * The home-screen rows: the pinned source's full section list, or
         * each ready source's primary section for the merged view.
         *
         * Providers keep their own order — it is part of their home design
         * (Wallhaven leads with Trending, for instance). A provider whose
         * [WallpaperProvider.sections] fails or comes back empty simply
         * contributes no row, mirroring how a failing source degrades in
         * [search]; only every provider failing at once is an error.
         */
        override suspend fun sections(sourceId: String?): NetworkResult<List<SourceSection>> {
            val providers = readyProviders()
            if (sourceId != null) {
                val pinned = providers.filter { it.first == sourceId }
                if (pinned.isEmpty()) {
                    // Same honesty rule as search: the pin outlived its
                    // package — say that, don't invent a connectivity error.
                    return NetworkResult.Failure(
                        NetworkError.Source("source '$sourceId' is not installed or not usable — see the Extensions tab"),
                    )
                }
                return pinned
                    .first()
                    .let { (id, provider) ->
                        runCatching { provider.sections() }.fold(
                            { sections -> NetworkResult.Success(sections.map { it.toSourceSection(id, provider) }) },
                            { failure -> NetworkResult.Failure(failure.toNetworkError()) },
                        )
                    }
            }
            if (providers.isEmpty()) {
                // Not an error: discovery may still be running. The caller
                // shows a loading state and the cold-start retry rescues.
                return NetworkResult.Success(emptyList())
            }
            return coroutineScope {
                val results =
                    providers
                        .map { (id, provider) ->
                            async {
                                runCatching {
                                    provider.sections().firstOrNull()?.toSourceSection(id, provider)
                                }
                            }
                        }.awaitAll()
                val rows = results.mapNotNull { it.getOrNull() }
                val firstFailure = results.firstOrNull { it.isFailure }
                if (rows.isEmpty() && firstFailure != null) {
                    NetworkResult.Failure(
                        (firstFailure.exceptionOrNull() ?: IllegalStateException()).toNetworkError(),
                    )
                } else {
                    NetworkResult.Success(rows)
                }
            }
        }

        /**
         * Translates a provider section to the host query pipeline: the
         * host-vocabulary keys of its [Filters] become typed query fields.
         *
         * `purity` is deliberately NOT read — the user's SFW setting owns
         * content ratings, applied by the browse ViewModel on top of this
         * query (the same rule [WallpaperSources.search] enforces per
         * item). Values outside the host vocabulary are dropped, which is
         * the documented Filters contract for keys a consumer cannot
         * express.
         */
        private fun HomeSection.toSourceSection(
            sourceId: String,
            provider: WallpaperProvider,
        ): SourceSection {
            val categories =
                filters
                    .valuesFor("category")
                    .mapNotNull { value ->
                        when (value) {
                            "general" -> WallpaperCategory.GENERAL
                            "anime" -> WallpaperCategory.ANIME
                            "people" -> WallpaperCategory.PEOPLE
                            else -> null
                        }
                    }.toSet()
            val sorting =
                when (filters.valuesFor("sorting").firstOrNull()) {
                    "date" -> WallpaperSorting.DATE
                    "random" -> WallpaperSorting.RANDOM
                    "relevance" -> WallpaperSorting.RELEVANCE
                    else -> WallpaperSorting.TOPLIST
                }
            return SourceSection(
                sourceId = sourceId,
                sourceName = provider.meta.name.ifBlank { sourceId.substringAfterLast('.') },
                sectionId = id,
                title = title,
                isDefault = id == HomeSection.DEFAULT_ID,
                query =
                    WallpaperQuery(
                        categories = categories.ifEmpty { WallpaperCategory.entries.toSet() },
                        sorting = sorting,
                        descending = !filters.isSelected("order", "asc"),
                        seed = filters.valuesFor("seed").firstOrNull(),
                    ),
            )
        }

        /** Extension manifest id to its loaded provider, READY extensions only. */
        private suspend fun readyProviders(): List<Pair<String, WallpaperProvider>> =
            extensions.installed.value
                .orEmpty()
                .filter { it.status == ExtensionStatus.READY }
                .mapNotNull { extension ->
                    val manifest = extension.manifest ?: return@mapNotNull null
                    when (val loaded = extensions.providerFor(extension)) {
                        is LoadResult.Loaded -> manifest.id to loaded.provider
                        is LoadResult.Failed -> null
                    }
                }

        /** Diagnoses WHY no source is usable, for the failure reason. */
        private fun noSourcesReason(): String {
            val installed = extensions.installed.value.orEmpty()
            return when {
                installed.isEmpty() -> "no wallpaper sources installed"
                installed.none { it.status == ExtensionStatus.READY } ->
                    "every installed package is corrupted or untrusted — see the Extensions tab"
                else -> "every installed source failed to load — see the Extensions tab"
            }
        }

        /**
         * The honest mapping: a transport failure keeps its type (a timeout
         * stays a timeout, offline stays offline), and everything else is
         * the SOURCE failing — never a connectivity claim.
         */
        private fun Throwable.toNetworkError(): NetworkError =
            when (this) {
                is ProviderTransportException -> error
                is SerializationException -> NetworkError.Serialization(this)
                is LinkageError ->
                    NetworkError.Source(
                        "source failed to bind its classes (${javaClass.simpleName}: $message) — " +
                            "update the app or reinstall the source",
                    )
                is ProviderHttpException -> NetworkError.Source("source failed: $message")
                else -> NetworkError.Source("source failed: ${message ?: javaClass.simpleName}")
            }

        private fun WallpaperQuery.toFilters(): Filters {
            val selections = mutableMapOf<String, Set<String>>()
            if (categories.isNotEmpty() && categories.size < WallpaperCategory.entries.size) {
                selections["category"] = categories.mapTo(mutableSetOf()) { it.name.lowercase() }
            }
            val purity =
                contentRatings
                    .mapNotNull { rating ->
                        when (rating) {
                            ContentRating.SFW -> "sfw"
                            ContentRating.SKETCHY -> "sketchy"
                            // Never requestable in V1; the post-filter enforces it anyway.
                            ContentRating.NSFW -> null
                        }
                    }.toSet()
            if (purity.isNotEmpty()) {
                selections["purity"] = purity
            }
            selections["sorting"] =
                setOf(
                    when (sorting) {
                        WallpaperSorting.TOPLIST -> "toplist"
                        WallpaperSorting.DATE -> "date"
                        WallpaperSorting.RANDOM -> "random"
                        WallpaperSorting.RELEVANCE -> "relevance"
                    },
                )
            if (!descending) {
                selections["order"] = setOf("asc")
            }
            seed?.takeIf { it.isNotBlank() }?.let { selections["seed"] = setOf(it) }
            return Filters.of(selections)
        }

        private fun ProviderRating.toCore(): ContentRating =
            when (this) {
                ProviderRating.SFW -> ContentRating.SFW
                ProviderRating.SKETCHY -> ContentRating.SKETCHY
                ProviderRating.NSFW -> ContentRating.NSFW
            }

        private fun ProviderWallpaper.toCore(): Wallpaper =
            Wallpaper(
                id = id,
                providerId = providerId,
                thumbUrl = thumbUrl,
                fullUrl = fullUrl,
                title = title,
                width = width,
                height = height,
                sourceUrl = null,
                contentRating = contentRating.toCore(),
            )
    }

/** How many merged tag suggestions survive per lookup (v1.0.9). */
private const val TAG_SUGGESTION_LIMIT = 8
