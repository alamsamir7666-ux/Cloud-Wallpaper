package com.cloudimage.feature.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cloudimage.core.model.Wallpaper

/**
 * The home feed: a Wallhaven-backed staggered masonry grid with search,
 * filters and infinite scroll.
 *
 * Paging is triggered by a prefetch buffer — when the user scrolls within
 * eight items of the end, the next page loads before they hit it.
 */
@Composable
fun BrowseScreen(
    onWallpaperClick: (Wallpaper) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BrowseViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showFilters by remember { mutableStateOf(false) }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    ) {
        SearchBarRow(
            searchText = state.searchText,
            onSearchTextChange = viewModel::onSearchTextChange,
            onSearchSubmit = viewModel::onSearchSubmit,
            onOpenFilters = { showFilters = true },
            filtersActive = state.filtersActive,
        )

        when {
            state.isFirstLoading -> FullScreenLoading()
            state.showFullscreenError ->
                FullScreenError(error = state.error!!, onRetry = viewModel::onRetry)

            state.wallpapers.isEmpty() -> EmptyResults()

            else ->
                BrowseGrid(
                    state = state,
                    onWallpaperClick = onWallpaperClick,
                    onLoadMore = viewModel::loadMore,
                )
        }
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
        modifier = modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
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

@Composable
private fun BrowseGrid(
    state: BrowseUiState,
    onWallpaperClick: (Wallpaper) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyStaggeredGridState()

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
private fun BrowseError.message(): String =
    when (this) {
        BrowseError.HTTP -> stringResource(R.string.browse_error_http)
        BrowseError.TIMEOUT -> stringResource(R.string.browse_error_timeout)
        BrowseError.OFFLINE -> stringResource(R.string.browse_error_offline)
        BrowseError.BAD_DATA -> stringResource(R.string.browse_error_bad_data)
    }

private const val PREFETCH_BUFFER = 8
