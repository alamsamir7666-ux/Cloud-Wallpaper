package com.cloudimage.feature.library

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.HistoryEdu
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.cloudimage.core.designsystem.WallpaperCard
import com.cloudimage.core.model.HistoryAction
import com.cloudimage.core.model.HistoryEntry
import com.cloudimage.core.model.Wallpaper

/** The two pages of the library. */
private enum class LibraryTab {
    FAVORITES,
    HISTORY,
}

/**
 * The user's own collection: saved favorites as a masonry grid on the
 * first tab, the view/apply/download history feed on the second.
 *
 * Both lists are Room streams, so a heart tapped in the detail screen
 * updates this screen live. Saved items deliberately ignore the SFW-only
 * setting — they were clamped at browse time and are the user's picks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onWallpaperClick: (Wallpaper) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableIntStateOf(LibraryTab.FAVORITES.ordinal) }
    var showClearDialog by remember { mutableStateOf(false) }
    val tab = LibraryTab.entries[selectedTab]

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 20.dp, top = 12.dp, end = 8.dp, bottom = 4.dp),
        ) {
            Text(
                text = stringResource(R.string.library_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            if (tab == LibraryTab.HISTORY && state.hasHistory) {
                IconButton(onClick = { showClearDialog = true }) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteSweep,
                        contentDescription = stringResource(R.string.library_clear_history),
                    )
                }
            }
        }

        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = tab == LibraryTab.FAVORITES,
                onClick = { selectedTab = LibraryTab.FAVORITES.ordinal },
                text = { Text(stringResource(R.string.library_tab_favorites)) },
            )
            Tab(
                selected = tab == LibraryTab.HISTORY,
                onClick = { selectedTab = LibraryTab.HISTORY.ordinal },
                text = { Text(stringResource(R.string.library_tab_history)) },
            )
        }

        when (tab) {
            LibraryTab.FAVORITES ->
                if (state.hasFavorites) {
                    FavoritesGrid(
                        state = state,
                        onWallpaperClick = onWallpaperClick,
                        onRemove = viewModel::removeFromFavorites,
                    )
                } else {
                    LibraryEmpty(
                        icon = Icons.Rounded.Favorite,
                        titleRes = R.string.library_empty_favorites_title,
                        bodyRes = R.string.library_empty_favorites_body,
                    )
                }

            LibraryTab.HISTORY ->
                if (state.hasHistory) {
                    HistoryList(history = state.history, onWallpaperClick = onWallpaperClick)
                } else {
                    LibraryEmpty(
                        icon = Icons.Rounded.HistoryEdu,
                        titleRes = R.string.library_empty_history_title,
                        bodyRes = R.string.library_empty_history_body,
                    )
                }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.library_clear_confirm_title)) },
            text = { Text(stringResource(R.string.library_clear_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        viewModel.clearHistory()
                    },
                ) { Text(stringResource(R.string.library_clear_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.library_clear_cancel))
                }
            },
        )
    }
}

@Composable
private fun FavoritesGrid(
    state: LibraryUiState,
    onWallpaperClick: (Wallpaper) -> Unit,
    onRemove: (Wallpaper) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(state.gridColumns),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalItemSpacing = 12.dp,
        modifier = modifier.fillMaxSize().testTag("library:favorites"),
    ) {
        items(
            count = state.favorites.size,
            key = { index ->
                "${state.favorites[index].wallpaper.providerId}:${state.favorites[index].wallpaper.id}"
            },
        ) { index ->
            val favorite = state.favorites[index]
            Box {
                WallpaperCard(
                    wallpaper = favorite.wallpaper,
                    onClick = { onWallpaperClick(favorite.wallpaper) },
                )
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                    modifier =
                        Modifier
                            .padding(6.dp)
                            .align(Alignment.TopEnd),
                ) {
                    IconButton(
                        onClick = { onRemove(favorite.wallpaper) },
                        modifier =
                            Modifier.size(36.dp).testTag(
                                "library:remove:${favorite.wallpaper.id}",
                            ),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Favorite,
                            tint = MaterialTheme.colorScheme.primary,
                            contentDescription =
                                stringResource(R.string.library_remove_favorite),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryList(
    history: List<HistoryEntry>,
    onWallpaperClick: (Wallpaper) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
        modifier = modifier.fillMaxSize().testTag("library:history"),
    ) {
        items(
            count = history.size,
            key = { index -> "${history[index].wallpaper.providerId}:${history[index].wallpaper.id}:${history[index].atMillis}" },
        ) { index ->
            val entry = history[index]
            HistoryRow(entry = entry, onClick = { onWallpaperClick(entry.wallpaper) })
            if (index < history.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
private fun HistoryRow(
    entry: HistoryEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .clickableRow(onClick)
                .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        AsyncImage(
            model = entry.wallpaper.thumbUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
        ) {
            Text(
                text = entry.wallpaper.title ?: "#${entry.wallpaper.id}",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.action.label(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = relativeTime(entry.atMillis),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = entry.action.icon(),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun HistoryAction.label(): String =
    when (this) {
        HistoryAction.VIEWED -> stringResource(R.string.library_action_viewed)
        HistoryAction.APPLIED -> stringResource(R.string.library_action_applied)
        HistoryAction.DOWNLOADED -> stringResource(R.string.library_action_downloaded)
    }

private fun HistoryAction.icon() =
    when (this) {
        HistoryAction.VIEWED -> Icons.Rounded.Visibility
        HistoryAction.APPLIED -> Icons.Rounded.Wallpaper
        HistoryAction.DOWNLOADED -> Icons.Rounded.Download
    }

/** "5 minutes ago" — frozen per timestamp so recomposition stays cheap. */
@Composable
private fun relativeTime(atMillis: Long): String = remember(atMillis) { DateUtils.getRelativeTimeSpanString(atMillis).toString() }

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)

@Composable
private fun LibraryEmpty(
    icon: ImageVector,
    titleRes: Int,
    bodyRes: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxSize().padding(32.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(bodyRes),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
