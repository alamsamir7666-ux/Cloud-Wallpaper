package com.cloudimage.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.ImageNotSupported
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cloudimage.core.designsystem.WallpaperCard
import com.cloudimage.core.model.Wallpaper
import com.cloudimage.core.search.ImageSearchError
import com.cloudimage.core.search.SearchSizeTier
import kotlinx.coroutines.delay

/**
 * The global image search (v1.2.0): a field, the whole web behind it.
 *
 * Google-Images-shaped: a search bar over a staggered grid of results —
 * each card naming the site it came from — with a size-tier chip row,
 * infinite scroll, and the shared search history on the idle screen.
 * Tapping a result hands the whole result list to the fullscreen viewer,
 * so preview, download, apply and share behave exactly like every other
 * wallpaper in the app.
 */
@Composable
fun SearchScreen(
    onWallpaperClick: (wallpapers: List<Wallpaper>, index: Int) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    var showClearHistoryDialog by remember { mutableStateOf(false) }
    val gridState = rememberLazyStaggeredGridState()

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SearchFieldRow(
                text = state.query,
                onTextChange = viewModel::onQueryChange,
                onSubmit = {
                    viewModel.onSearchSubmit()
                    focusManager.clearFocus()
                },
            )

            SizeTierRow(
                selected = state.sizeTier,
                onSelect = viewModel::onSizeTierSelected,
            )

            when {
                state.isFirstLoading -> FullScreenLoading()

                state.isIdle ->
                    IdleScreen(
                        history = state.history,
                        onHistorySelected = viewModel::onSuggestionSelected,
                        onClearHistory = { showClearHistoryDialog = true },
                    )

                state.error != null && state.results.isEmpty() ->
                    FullScreenError(
                        error = state.error!!,
                        errorDetail = state.errorDetail,
                        onRetry = viewModel::onRetry,
                    )

                state.showEmptyState -> EmptyResults()

                else ->
                    ResultsGrid(
                        state = state,
                        gridState = gridState,
                        onWallpaperClick = onWallpaperClick,
                        onLoadMore = viewModel::loadMore,
                    )
            }
        }

        if (showClearHistoryDialog) {
            ClearHistoryDialog(
                onConfirm = {
                    viewModel.onClearHistory()
                    showClearHistoryDialog = false
                },
                onDismiss = { showClearHistoryDialog = false },
            )
        }
    }
}

@Composable
private fun SearchFieldRow(
    text: String,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = text,
        onValueChange = onTextChange,
        placeholder = { Text(stringResource(R.string.search_hint)) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = {
            if (text.isNotEmpty()) {
                IconButton(onClick = { onTextChange("") }) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.search_clear_query),
                    )
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        singleLine = true,
        shape = CircleShape,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag("search:field"),
    )
}

/**
 * The size-tier chip row — the one filter a wallpaper app cannot live
 * without. Picking a tier re-runs the active search immediately.
 */
@Composable
private fun SizeTierRow(
    selected: SearchSizeTier,
    onSelect: (SearchSizeTier) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        val tiers = SearchSizeTier.entries
        tiers.forEach { tier ->
            FilterChip(
                selected = tier == selected,
                onClick = { onSelect(tier) },
                label = { Text(stringResource(tier.labelRes())) },
            )
        }
    }
}

/** The idle screen: history when there is any, an invitation when there is none. */
@Composable
private fun IdleScreen(
    history: List<String>,
    onHistorySelected: (String) -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (history.isEmpty()) {
        MessagePane(
            icon = Icons.Rounded.Public,
            title = stringResource(R.string.search_idle_title),
            message = stringResource(R.string.search_idle_message),
            modifier = modifier,
        )
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.search_history_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClearHistory) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = stringResource(R.string.search_history_clear),
                )
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier =
                Modifier
                    .fillMaxSize()
                    .testTag("search:history"),
        ) {
            items(history) { entry ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onHistorySelected(entry) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Icon(
                        Icons.Rounded.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = entry,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultsGrid(
    state: SearchViewModel.SearchUiState,
    gridState: LazyStaggeredGridState,
    onWallpaperClick: (wallpapers: List<Wallpaper>, index: Int) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // True once the user scrolls within eight items of the end — the same
    // prefetch contract as the browse feed.
    val closeToTheEnd by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - PREFETCH_BUFFER
        }
    }
    LaunchedEffect(closeToTheEnd) {
        if (closeToTheEnd) onLoadMore()
    }
    LaunchedEffect(state.results.size) {
        if (closeToTheEnd) onLoadMore()
    }

    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(state.gridColumns),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalItemSpacing = 12.dp,
        state = gridState,
        modifier =
            modifier
                .fillMaxSize()
                .testTag("search:grid"),
    ) {
        items(
            count = state.results.size,
            key = { index -> "${state.results[index].providerId}:${state.results[index].id}" },
        ) { index ->
            val wallpaper = state.results[index]
            WallpaperCard(
                wallpaper = wallpaper,
                onClick = { onWallpaperClick(state.results, index) },
                // Google-Images-style provenance: every card names the site it
                // came from, so the grid reads as the web, not as one source.
                providerLabel = wallpaper.title,
                // Google-Images-style dimensions: the true pixel size a card
                // could state — unknown sizes stay silent rather than guess.
                dimensionLabel = wallpaper.dimensionsLabel(),
            )
        }

        if (state.isLoadingMore || (state.error != null && state.results.isNotEmpty())) {
            item(span = StaggeredGridItemSpan.FullLine, key = "footer") {
                GridFooter(
                    isLoadingMore = state.isLoadingMore,
                    error = state.error,
                    errorDetail = state.errorDetail,
                    onRetry = onLoadMore,
                )
            }
        }
    }
}

@Composable
private fun GridFooter(
    isLoadingMore: Boolean,
    error: ImageSearchError?,
    errorDetail: String?,
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
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    Text(
                        text = error.message(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    errorDetail?.let { detail ->
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                }
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.search_retry))
                }
            }
    }
}

@Composable
private fun FullScreenLoading(modifier: Modifier = Modifier) {
    // The backend's real searches routinely take tens of seconds, and a
    // silent spinner reads as broken long before then. After eight
    // seconds, say so — the reference app's lesson, kept.
    var showSlowHint by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SLOW_HINT_DELAY_MS)
        showSlowHint = true
    }
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(48.dp))
            if (showSlowHint) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.search_slow_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
        }
    }
}

@Composable
private fun FullScreenError(
    error: ImageSearchError,
    errorDetail: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Rounded.Public,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = error.message(),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            errorDetail?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.search_retry))
            }
        }
    }
}

@Composable
private fun EmptyResults(modifier: Modifier = Modifier) {
    MessagePane(
        icon = Icons.Rounded.ImageNotSupported,
        title = stringResource(R.string.search_empty_title),
        message = stringResource(R.string.search_empty_message),
        modifier = modifier,
    )
}

@Composable
private fun MessagePane(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ClearHistoryDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.search_history_clear_title)) },
        text = { Text(stringResource(R.string.search_history_clear_text)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.search_history_clear_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.search_history_clear_dismiss))
            }
        },
    )
}

@Composable
private fun ImageSearchError.message(): String =
    when (this) {
        ImageSearchError.RATE_LIMITED -> stringResource(R.string.search_error_rate_limited)
        ImageSearchError.NETWORK -> stringResource(R.string.search_error_network)
        ImageSearchError.TIMEOUT -> stringResource(R.string.search_error_timeout)
        ImageSearchError.SERVER -> stringResource(R.string.search_error_server)
        ImageSearchError.BAD_RESPONSE -> stringResource(R.string.search_error_bad_response)
    }

@Composable
private fun SearchSizeTier.labelRes(): Int =
    when (this) {
        SearchSizeTier.ANY -> R.string.search_size_any
        SearchSizeTier.HD -> R.string.search_size_hd
        SearchSizeTier.FHD -> R.string.search_size_fhd
        SearchSizeTier.QHD -> R.string.search_size_qhd
        SearchSizeTier.UHD -> R.string.search_size_uhd
    }

private const val PREFETCH_BUFFER = 8
private const val SLOW_HINT_DELAY_MS = 8_000L

/** "1920 × 1080" when the engine could verify the size; null when it could not. */
private fun Wallpaper.dimensionsLabel(): String? {
    val w = width
    val h = height
    return if (w != null && h != null && w > 0 && h > 0) "$w × $h" else null
}
