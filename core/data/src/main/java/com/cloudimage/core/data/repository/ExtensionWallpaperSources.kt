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
import com.cloudimage.provider.api.Capability
import com.cloudimage.provider.api.Filters
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
import java.io.IOException
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
 * package checksum. Sources that fail to load are silently skipped —
 * broken plugins degrade to absence, never to a dead feed.
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

        init {
            appScope.launch { refresh() }
        }

        override suspend fun refresh() {
            extensions.refresh()
            state.value =
                (extensions.installed.value.orEmpty())
                    .filter { it.status == ExtensionStatus.READY }
                    .mapNotNull { extension ->
                        val manifest = extension.manifest ?: return@mapNotNull null
                        when (val loaded = extensions.providerFor(extension)) {
                            is LoadResult.Loaded ->
                                SourceInfo(
                                    id = manifest.id,
                                    name = loaded.provider.meta.name.ifBlank { manifest.name },
                                    requiresApiKey = loaded.provider.meta.requiresApiKey,
                                )
                            is LoadResult.Failed -> null
                        }
                    }
        }

        override suspend fun search(
            query: WallpaperQuery,
            page: Int,
        ): NetworkResult<Page> {
            val providers = readyProviders()
            if (providers.isEmpty()) {
                return NetworkResult.Failure(NetworkError.Io(IOException("no wallpaper sources installed")))
            }
            val filters = query.toFilters()
            val ratings = query.contentRatings

            return coroutineScope {
                val results =
                    providers
                        .map { provider -> async { provider.dispatch(query, page, filters) } }
                        .awaitAll()

                val pages = results.mapNotNull { it.getOrNull() }
                val firstFailure = results.firstOrNull { it.isFailure }

                if (pages.isEmpty() && firstFailure != null) {
                    NetworkResult.Failure((firstFailure.exceptionOrNull() ?: IllegalStateException()).toNetworkError())
                } else {
                    NetworkResult.Success(
                        Page(
                            wallpapers =
                                pages
                                    .flatMap { it.wallpapers }
                                    .filter { it.contentRating.toCore() in ratings }
                                    .map { it.toCore() },
                            nextPage = pages.mapNotNull { it.nextPage }.maxOrNull(),
                        ),
                    )
                }
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

        private suspend fun readyProviders(): List<WallpaperProvider> =
            extensions.installed.value.orEmpty()
                .filter { it.status == ExtensionStatus.READY }
                .mapNotNull { extension ->
                    when (val loaded = extensions.providerFor(extension)) {
                        is LoadResult.Loaded -> loaded.provider
                        is LoadResult.Failed -> null
                    }
                }

        private fun Throwable.toNetworkError(): NetworkError =
            when (this) {
                is SerializationException -> NetworkError.Serialization(this)
                is ProviderHttpException -> NetworkError.Io(IOException(message, this))
                else -> NetworkError.Io(IOException(message ?: "source failed", this))
            }

        private fun WallpaperQuery.toFilters(): Filters {
            val selections = mutableMapOf<String, Set<String>>()
            if (categories.isNotEmpty() && categories.size < WallpaperCategory.entries.size) {
                selections["category"] = categories.mapTo(mutableSetOf()) { it.name.lowercase() }
            }
            val purity =
                contentRatings.mapNotNull { rating ->
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
