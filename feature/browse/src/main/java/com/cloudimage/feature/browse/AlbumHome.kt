package com.cloudimage.feature.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cloudimage.core.data.repository.SourceAlbum
import com.cloudimage.core.designsystem.AlbumCard
import com.cloudimage.core.designsystem.WallpaperCard
import com.cloudimage.core.model.Wallpaper

/**
 * The album paradigm's home (v1.1.0) — what the browse screen becomes when
 * the feed is pinned to a source that declares
 * [com.cloudimage.core.data.repository.SourceCapability.ALBUMS].
 *
 * There are no swipeable tabs: a single "Home" header (the source's own
 * homepage albums) is the root of a drill-in stack the Realme-style
 * category sidebar navigates — sidebar → category → album → the ordinary
 * fullscreen viewer. Every level is one complete grid, because
 * album-style sites serve their pages whole; the scope chip above the
 * grid is the way back one level (the system back does the same).
 */
@Composable
internal fun AlbumsHome(
    scope: AlbumScope,
    content: AlbumContentState,
    gridColumns: Int,
    onAlbumClick: (SourceAlbum) -> Unit,
    onWallpaperClick: (wallpapers: List<Wallpaper>, index: Int) -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .testTag("browse:albums"),
    ) {
        when (scope) {
            is AlbumScope.Home -> AlbumHomeHeader()
            is AlbumScope.Category -> AlbumScopeBar(title = scope.category.name, onBack = onBack)
            is AlbumScope.Album -> AlbumScopeBar(title = scope.album.title, onBack = onBack)
            is AlbumScope.Search ->
                AlbumScopeBar(
                    title = stringResource(R.string.browse_album_search_results, scope.query),
                    onBack = onBack,
                )
        }

        if (scope is AlbumScope.Album) {
            AlbumWallpaperGrid(
                content = content,
                onWallpaperClick = onWallpaperClick,
                onRetry = onRetry,
            )
        } else {
            AlbumGrid(
                content = content,
                gridColumns = gridColumns,
                onAlbumClick = onAlbumClick,
                onRetry = onRetry,
            )
        }
    }
}

/**
 * The single "Home" tab (v1.1.0) — album-style sources have no swipeable
 * tabs, so this is a label, not a switch: the same pill geometry the tab
 * bar uses, minus the bar.
 */
@Composable
private fun AlbumHomeHeader(modifier: Modifier = Modifier) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)
                .testTag("browse:album-home-tab"),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Text(
                text = stringResource(R.string.browse_album_home),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
            )
        }
    }
}

/**
 * The drill-in scope bar (v1.1.0): a chip naming the category, album or
 * search the grid is scoped to, with a back arrow — the album paradigm's
 * equivalent of the flat grid's See-all chip.
 */
@Composable
private fun AlbumScopeBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.browse_album_scope_close),
            )
        }
        InputChip(
            selected = true,
            onClick = onBack,
            label = {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            modifier = Modifier.testTag("browse:album-scope-chip"),
        )
    }
}

/** The album shelf: a uniform grid of album covers, placeholders and all. */
@Composable
private fun AlbumGrid(
    content: AlbumContentState,
    gridColumns: Int,
    onAlbumClick: (SourceAlbum) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(gridColumns),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            modifier
                .fillMaxSize()
                .testTag("browse:album-grid"),
    ) {
        if (content.isLoading && content.albums.isEmpty()) {
            items(count = ALBUM_PLACEHOLDER_COUNT, key = { "placeholder:$it" }) {
                AlbumCardPlaceholder()
            }
        } else {
            items(content.albums, key = { it.sourceId + ":" + it.id }) { album ->
                AlbumCard(
                    title = album.title,
                    coverUrl = album.coverUrl,
                    wallpaperCount = album.wallpaperCount,
                    onClick = { onAlbumClick(album) },
                )
            }

            if (content.error != null) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "error") {
                    AlbumGridFooter(
                        error = content.error,
                        errorDetail = content.errorDetail,
                        onRetry = onRetry,
                    )
                }
            }

            if (!content.isLoading && content.albums.isEmpty() && content.error == null) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 48.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.browse_album_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** One album's wallpapers: the feed's own staggered grid, complete list. */
@Composable
private fun AlbumWallpaperGrid(
    content: AlbumContentState,
    onWallpaperClick: (wallpapers: List<Wallpaper>, index: Int) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(ALBUM_GRID_COLUMNS),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalItemSpacing = 12.dp,
        modifier =
            modifier
                .fillMaxSize()
                .testTag("browse:album-wallpapers"),
    ) {
        if (content.isLoading && content.wallpapers.isEmpty()) {
            items(count = ALBUM_PLACEHOLDER_COUNT, key = { "placeholder:$it" }) { index ->
                WallpaperCardPlaceholder(tall = index % 3 == 0)
            }
        } else {
            items(
                count = content.wallpapers.size,
                key = { index ->
                    "${content.wallpapers[index].providerId}:${content.wallpapers[index].id}"
                },
            ) { index ->
                WallpaperCard(
                    wallpaper = content.wallpapers[index],
                    onClick = { onWallpaperClick(content.wallpapers, index) },
                )
            }

            if (content.error != null) {
                item(span = StaggeredGridItemSpan.FullLine, key = "error") {
                    AlbumGridFooter(
                        error = content.error,
                        errorDetail = content.errorDetail,
                        onRetry = onRetry,
                    )
                }
            }

            if (!content.isLoading && content.wallpapers.isEmpty() && content.error == null) {
                item(span = StaggeredGridItemSpan.FullLine, key = "empty") {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 48.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.browse_album_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** The retry row under a failed album load. */
@Composable
private fun AlbumGridFooter(
    error: BrowseError,
    errorDetail: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.fillMaxWidth().padding(16.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.browse_retry))
            }
        }
    }
}

/** Skeleton album cover shown while the shelf's first load is in flight. */
@Composable
private fun AlbumCardPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

/** Skeleton wallpaper card — same geometry the sectioned home uses. */
@Composable
private fun WallpaperCardPlaceholder(tall: Boolean) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(if (tall) 240.dp else 180.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
    )
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

private const val ALBUM_GRID_COLUMNS = 2
private const val ALBUM_PLACEHOLDER_COUNT = 8
