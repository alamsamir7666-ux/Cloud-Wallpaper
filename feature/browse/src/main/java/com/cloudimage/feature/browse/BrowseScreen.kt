package com.cloudimage.feature.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cloudimage.core.designsystem.WallpaperCard
import com.cloudimage.core.model.Wallpaper

/**
 * The home feed: a staggered masonry grid over every installed source, with
 * search, filters, a CloudStream-style source switcher (v1.0.8) and infinite
 * scroll.
 *
 * The switcher mirrors CloudStream's home: an extended FAB pinned to the
 * bottom corner that names the active source and opens a bottom-sheet list
 * of every installed one. Paging is triggered by a prefetch buffer — when
 * the user scrolls within eight items of the end, the next page loads
 * before they hit it.
 */
@Composable
fun BrowseScreen(
    onWallpaperClick: (Wallpaper) -> Unit,
    onOpenExtensions: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BrowseViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showFilters by remember { mutableStateOf(false) }
    var showSourceSheet by remember { mutableStateOf(false) }

    // Hoisted so the source FAB can react to the feed's scroll direction.
    val gridState = rememberLazyStaggeredGridState()

    // Scrolling down the feed shrinks the FAB to its icon; scrolling back
    // up re-extends it — the CloudStream home behavior. The index/offset
    // sum stays monotonic across item swaps; the small threshold ignores
    // sub-pixel jitter.
    var fabExpanded by remember { mutableStateOf(true) }
    LaunchedEffect(gridState) {
        var lastPosition = 0
        snapshotFlow {
            gridState.firstVisibleItemIndex * 1_000_000 + gridState.firstVisibleItemScrollOffset
        }.collect { position ->
            val delta = position - lastPosition
            if (delta > 0) {
                fabExpanded = false
            } else if (delta < -5) {
                fabExpanded = true
            }
            lastPosition = position
        }
    }

    // A fresh feed (source switch, retry) starts with the FAB extended.
    LaunchedEffect(state.selectedSourceId) { fabExpanded = true }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SearchBarRow(
                searchText = state.searchText,
                onSearchTextChange = viewModel::onSearchTextChange,
                onSearchSubmit = viewModel::onSearchSubmit,
                onOpenFilters = { showFilters = true },
                filtersActive = state.filtersActive,
            )

            when {
                state.isFirstLoading -> FullScreenLoading()
                state.showApiKeyPrompt ->
                    ApiKeyPrompt(
                        onShowAllSources = { viewModel.onSourceSelected(null) },
                        onOpenExtensions = onOpenExtensions,
                    )

                state.showFullscreenError ->
                    FullScreenError(error = state.error!!, onRetry = viewModel::onRetry)

                state.showNoSources -> NoSources()

                state.wallpapers.isEmpty() -> EmptyResults()

                else ->
                    BrowseGrid(
                        state = state,
                        gridState = gridState,
                        onWallpaperClick = onWallpaperClick,
                        onLoadMore = viewModel::loadMore,
                    )
            }
        }

        // The CloudStream home FAB: names the active source, opens the
        // switcher sheet. Appears as soon as one usable source exists.
        if (state.sourceBar.isNotEmpty()) {
            SourceFab(
                text =
                    state.sourceBar.firstOrNull { it.id == state.selectedSourceId }?.name
                        ?: stringResource(R.string.browse_source_all),
                expanded = fabExpanded,
                onClick = { showSourceSheet = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }

    if (showSourceSheet) {
        SourceSheet(
            sources = state.sourceBar,
            selectedSourceId = state.selectedSourceId,
            onSourceSelected = viewModel::onSourceSelected,
            onOpenExtensions = {
                showSourceSheet = false
                onOpenExtensions()
            },
            onDismiss = { showSourceSheet = false },
        )
    }

    if (showFilters) {
        FilterSheet(
            query = state.query,
            sfwOnly = state.sfwOnly,
            onApply = viewModel::onQueryChange,
            onDismiss = { showFilters = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBarRow(
    searchText: String,
    onSearchTextChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    onOpenFilters: () -> Unit,
    filtersActive: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        OutlinedTextField(
            value = searchText,
            onValueChange = onSearchTextChange,
            placeholder = { Text(stringResource(R.string.browse_search_hint)) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                if (searchText.isNotEmpty()) {
                    IconButton(onClick = { onSearchTextChange("") }) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.browse_clear_search),
                        )
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearchSubmit() }),
            singleLine = true,
            shape = CircleShape,
            modifier = Modifier.weight(1f).testTag("browse:search"),
        )
        BadgedBox(
            badge = {
                if (filtersActive) {
                    Badge()
                }
            },
        ) {
            IconButton(onClick = onOpenFilters) {
                Icon(
                    Icons.Rounded.Tune,
                    contentDescription = stringResource(R.string.browse_open_filters),
                )
            }
        }
    }
}

/**
 * The CloudStream home "Source" button: an extended FAB pinned to the
 * feed's bottom corner that names the active source and opens the source
 * sheet. Scrolling down the feed shrinks it to its icon; scrolling up
 * extends it again.
 */
@Composable
private fun SourceFab(
    text: String,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ExtendedFloatingActionButton(
        text = {
            Text(
                text = text,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        icon = {
            Icon(imageVector = Icons.Rounded.FilterList, contentDescription = null)
        },
        onClick = onClick,
        expanded = expanded,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.testTag("browse:source-fab"),
    )
}

/**
 * The source switcher sheet, modeled on CloudStream's provider picker: a
 * single-choice list — "All sources", then every installed source sorted
 * alphabetically — where tapping an entry applies it immediately and
 * dismisses the sheet. The active entry carries a check mark and the
 * source color; sources still missing their API key show a key hint.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceSheet(
    sources: List<BrowseSource>,
    selectedSourceId: String?,
    onSourceSelected: (String?) -> Unit,
    onOpenExtensions: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = modifier.testTag("browse:source-sheet"),
    ) {
        // CloudStream sorts its provider list alphabetically in the sheet.
        val sorted = remember(sources) { sources.sortedBy { it.name.lowercase() } }

        Column(
            modifier =
                Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        ) {
            SourceSheetRow(
                label = stringResource(R.string.browse_source_all),
                selected = selectedSourceId == null,
                onClick = {
                    onSourceSelected(null)
                    onDismiss()
                },
                modifier = Modifier.testTag("browse:source:all"),
            )
            sorted.forEach { source ->
                SourceSheetRow(
                    label = source.name,
                    selected = selectedSourceId == source.id,
                    needsApiKey = source.needsApiKey,
                    onClick = {
                        onSourceSelected(source.id)
                        onDismiss()
                    },
                    modifier = Modifier.testTag("browse:source:${source.id}"),
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // CloudStream ends its picker with per-provider actions; the
            // Cloudimage equivalent is the jump to extension management.
            SourceSheetRow(
                label = stringResource(R.string.browse_source_manage),
                selected = false,
                leadingIcon = Icons.Rounded.Extension,
                onClick = onOpenExtensions,
                modifier = Modifier.testTag("browse:source:manage"),
            )
        }
    }
}

/** One sheet entry: a bold label, a check mark when active, a key hint. */
@Composable
private fun SourceSheetRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    needsApiKey: Boolean = false,
    leadingIcon: ImageVector? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .heightIn(min = 52.dp)
                .padding(horizontal = 24.dp, vertical = 6.dp),
    ) {
        // The leading slot keeps every label aligned whether or not the
        // row is checked or iconed.
        Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            when {
                selected ->
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )

                leadingIcon != null ->
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color =
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (needsApiKey) {
            Icon(
                imageVector = Icons.Rounded.Key,
                contentDescription = stringResource(R.string.browse_source_needs_key),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun BrowseGrid(
    state: BrowseUiState,
    gridState: LazyStaggeredGridState,
    onWallpaperClick: (Wallpaper) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // True once the user scrolls within eight items of the end. Reading
    // layoutInfo through derivedStateOf keeps recompositions cheap.
    val closeToTheEnd by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - PREFETCH_BUFFER
        }
    }

    // Re-check after every successful append too: a short first page may
    // already sit inside the prefetch window without further scrolling.
    LaunchedEffect(closeToTheEnd) {
        if (closeToTheEnd) onLoadMore()
    }
    LaunchedEffect(state.wallpapers.size) {
        if (closeToTheEnd) onLoadMore()
    }

    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(state.gridColumns),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalItemSpacing = 12.dp,
        state = gridState,
        modifier = modifier.fillMaxSize().testTag("browse:grid"),
    ) {
        items(
            count = state.wallpapers.size,
            key = { index -> "${state.wallpapers[index].providerId}:${state.wallpapers[index].id}" },
        ) { index ->
            val wallpaper = state.wallpapers[index]
            WallpaperCard(
                wallpaper = wallpaper,
                onClick = { onWallpaperClick(wallpaper) },
            )
        }

        if (state.isLoadingMore || state.error != null) {
            item(span = StaggeredGridItemSpan.FullLine, key = "footer") {
                BrowseGridFooter(
                    isLoadingMore = state.isLoadingMore,
                    error = state.error,
                    onRetry = onLoadMore,
                )
            }
        }
    }
}

@Composable
private fun BrowseGridFooter(
    isLoadingMore: Boolean,
    error: BrowseError?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        isLoadingMore ->
            Box(modifier = modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            }

        error != null ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                Text(
                    text = error.message(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f, fill = false),
                )
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.browse_retry))
                }
            }
    }
}

@Composable
private fun FullScreenLoading(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(48.dp))
    }
}

@Composable
private fun FullScreenError(
    error: BrowseError,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxSize().padding(32.dp),
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.errorContainer) {
            Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                Text("!", color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        Text(
            text = stringResource(R.string.browse_error_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = error.message(),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onRetry) {
            Text(stringResource(R.string.browse_retry))
        }
    }
}

@Composable
private fun EmptyResults(modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxSize().padding(32.dp).testTag("browse:empty"),
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
        Text(
            text = stringResource(R.string.browse_empty_results_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.browse_empty_results_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NoSources(modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxSize().padding(32.dp).testTag("browse:no-sources"),
    ) {
        Icon(
            imageVector = Icons.Rounded.Extension,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
        Text(
            text = stringResource(R.string.browse_no_sources_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.browse_no_sources_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ApiKeyPrompt(
    onShowAllSources: () -> Unit,
    onOpenExtensions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxSize().padding(32.dp).testTag("browse:api-key-prompt"),
    ) {
        Icon(
            imageVector = Icons.Rounded.Key,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
        Text(
            text = stringResource(R.string.browse_api_key_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.browse_api_key_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            FilledTonalButton(onClick = onOpenExtensions) {
                Text(stringResource(R.string.browse_api_key_open_extensions))
            }
            TextButton(onClick = onShowAllSources) {
                Text(stringResource(R.string.browse_api_key_show_all))
            }
        }
    }
}

@Composable
private fun BrowseError.message(): String =
    when (this) {
        BrowseError.HTTP -> stringResource(R.string.browse_error_http)
        BrowseError.TIMEOUT -> stringResource(R.string.browse_error_timeout)
        BrowseError.OFFLINE -> stringResource(R.string.browse_error_offline)
        BrowseError.BAD_DATA -> stringResource(R.string.browse_error_bad_data)
        BrowseError.SOURCE -> stringResource(R.string.browse_error_source)
    }

private const val PREFETCH_BUFFER = 8
