package com.cloudimage.feature.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.cloudimage.core.model.Wallpaper

/**
 * The sectioned home (v1.0.9) — CloudStream's `mainPage` model: a vertical
 * list of titled section rows, each a horizontally scrolling carousel with
 * its own pagination. Row headers carry a chevron that opens the staggered
 * grid scoped to the section (See all), mirroring CloudStream's
 * `home_child_more_info` header over its horizontal RecyclerView.
 */
@Composable
internal fun SectionsHome(
    sections: List<BrowseSectionState>,
    listState: LazyListState,
    onWallpaperClick: (Wallpaper) -> Unit,
    onSeeAll: (String) -> Unit,
    onLoadMoreSection: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(bottom = 96.dp),
        modifier =
            modifier
                .fillMaxSize()
                .testTag("browse:sections"),
    ) {
        items(sections, key = { it.key }) { section ->
            SectionRow(
                section = section,
                onWallpaperClick = onWallpaperClick,
                onSeeAll = onSeeAll,
                onLoadMoreSection = onLoadMoreSection,
            )
        }
    }
}

/** One titled carousel: header row plus the horizontally paging feed. */
@Composable
private fun SectionRow(
    section: BrowseSectionState,
    onWallpaperClick: (Wallpaper) -> Unit,
    onSeeAll: (String) -> Unit,
    onLoadMoreSection: (String) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 8.dp).testTag("browse:section:${section.key}")) {
        SectionHeader(
            title = section.title,
            onSeeAll = { onSeeAll(section.key) },
        )
        SectionCarousel(
            section = section,
            onWallpaperClick = onWallpaperClick,
            onLoadMore = { onLoadMoreSection(section.key) },
        )
    }
}

/**
 * The row header — CloudStream's pattern: the whole header is the See-all
 * target, with a trailing chevron as the only hint. The a11y description
 * says what tapping does.
 */
@Composable
private fun SectionHeader(
    title: String,
    onSeeAll: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onSeeAll)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag("browse:see-all"),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = stringResource(R.string.browse_see_all),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The horizontal feed with prefetch pagination — the carousel asks for the
 * next page while the user is still six cards away from the end, exactly
 * like the staggered grid's buffer.
 */
@Composable
private fun SectionCarousel(
    section: BrowseSectionState,
    onWallpaperClick: (Wallpaper) -> Unit,
    onLoadMore: () -> Unit,
) {
    val rowState = rememberLazyListState()

    // True once the carousel scrolls within six cards of its end.
    val closeToTheEnd by remember {
        derivedStateOf {
            val info = rowState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - SECTION_PREFETCH_BUFFER
        }
    }

    // Re-check after every successful append too: a short first page may
    // already sit inside the prefetch window without further scrolling.
    LaunchedEffect(closeToTheEnd) {
        if (closeToTheEnd) onLoadMore()
    }
    LaunchedEffect(section.wallpapers.size) {
        if (closeToTheEnd) onLoadMore()
    }

    LazyRow(
        state = rowState,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.heightIn(min = CARD_HEIGHT + 4.dp),
    ) {
        if (section.isFirstLoading) {
            items(PLACEHOLDER_COUNT) { index ->
                SectionCardPlaceholder(modifier = Modifier.testTag("browse:section-placeholder:$index"))
            }
        } else {
            items(
                section.wallpapers,
                key = { "${it.providerId}:${it.id}" },
            ) { wallpaper ->
                SectionWallpaperCard(
                    wallpaper = wallpaper,
                    onClick = { onWallpaperClick(wallpaper) },
                )
            }

            if (section.isLoadingMore) {
                item(key = "section-loading") {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier =
                            Modifier
                                .height(CARD_HEIGHT)
                                .width(SHORT_CARD_WIDTH)
                                .padding(horizontal = 4.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    }
                }
            }

            if (section.error != null && !section.endReached) {
                item(key = "section-error") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .height(CARD_HEIGHT)
                                .width(SHORT_CARD_WIDTH)
                                .padding(horizontal = 4.dp),
                    ) {
                        Column {
                            Text(
                                text = section.error.message(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(onClick = onLoadMore) {
                                Text(stringResource(R.string.browse_retry))
                            }
                        }
                    }
                }
            }

            if (section.endReached && section.wallpapers.isEmpty()) {
                item(key = "section-empty") {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier =
                            Modifier
                                .height(CARD_HEIGHT)
                                .width(SHORT_CARD_WIDTH * 2)
                                .padding(horizontal = 4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.browse_section_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * One carousel card: fixed height, width derived from the wallpaper's real
 * aspect ratio (clamped to the same band as the grid card, so a 21:9
 * panorama cannot dwarf the row and a 9:16 shot cannot become a sliver).
 */
@Composable
private fun SectionWallpaperCard(
    wallpaper: Wallpaper,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        modifier =
            Modifier
                .height(CARD_HEIGHT)
                .aspectRatio(
                    wallpaper.aspectRatio?.coerceIn(minimumValue = 0.5f, maximumValue = 2.4f)
                        ?: 1.4f,
                ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model =
                    ImageRequest
                        .Builder(LocalContext.current)
                        .data(wallpaper.thumbUrl)
                        .crossfade(durationMillis = 220)
                        .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
                error = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Skeleton card shown while the row's first page loads. */
@Composable
private fun SectionCardPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .height(CARD_HEIGHT)
                .width(SHORT_CARD_WIDTH)
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

private val CARD_HEIGHT = 200.dp
private val SHORT_CARD_WIDTH = 140.dp
private const val SECTION_PREFETCH_BUFFER = 6
private const val PLACEHOLDER_COUNT = 3
