package com.cloudimage.feature.browse

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridScope
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cloudimage.core.designsystem.WallpaperCard
import com.cloudimage.core.model.Wallpaper

/**
 * The tabbed home (v1.0.10) — CloudStream's `mainPage` model, one tab per
 * feed instead of one row per feed: the personal "Recently applied" tab
 * leads the bar whenever it exists, followed by the provider's declared
 * sections. The bar is a scrollable pill tab row; the pages swipe through a
 * HorizontalPager, and every section page is a staggered grid fed by the
 * section's own pagination — one feed at a time, nothing stacked.
 *
 * Tab selection lives in the ViewModel ([BrowseUiState.selectedHomeTab]):
 * taps and settled swipes both land in [onTabSelected], so the bar and the
 * pager can never disagree about which feed is active.
 */
@Composable
internal fun SectionsHome(
    sections: List<BrowseSectionState>,
    recentlyApplied: List<Wallpaper>,
    tabs: List<HomeTab>,
    selectedTab: HomeTab?,
    onTabSelected: (String) -> Unit,
    activeGridState: MutableState<LazyStaggeredGridState?>,
    onWallpaperClick: (Wallpaper) -> Unit,
    onLoadMoreSection: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tabs.isEmpty()) return
    val selectedIndex = tabs.indexOf(selectedTab).takeIf { it >= 0 } ?: 0
    val pagerState =
        rememberPagerState(initialPage = selectedIndex, pageCount = { tabs.size })

    // A tap on the bar moves the pager (animated); the state only ever
    // names a tab that exists, so the index is always in bounds.
    LaunchedEffect(selectedIndex, tabs.size) {
        if (pagerState.settledPage != selectedIndex) {
            pagerState.animateScrollToPage(selectedIndex)
        }
    }
    // A swipe that settles on a page is a selection like any tap.
    LaunchedEffect(pagerState.settledPage, tabs.size) {
        val page = pagerState.settledPage
        if (page in tabs.indices && page != selectedIndex) {
            onTabSelected(tabs[page].key)
        }
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .testTag("browse:sections"),
    ) {
        // One feed alone needs no bar — the tab would be a label, not a
        // switch; the pager still renders the single page.
        if (tabs.size > 1) {
            HomeTabRow(
                tabs = tabs,
                selectedIndex = selectedIndex,
                onSelect = { index -> onTabSelected(tabs[index].key) },
            )
        }
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val tab = tabs[page]
            val isCurrentPage = pagerState.settledPage == page
            if (tab.key == RECENTLY_APPLIED_TAB_KEY) {
                RecentlyAppliedGrid(
                    wallpapers = recentlyApplied,
                    isCurrentPage = isCurrentPage,
                    activeGridState = activeGridState,
                    onWallpaperClick = onWallpaperClick,
                )
            } else {
                val section = sections.firstOrNull { it.key == tab.key } ?: return@HorizontalPager
                SectionTabGrid(
                    section = section,
                    isCurrentPage = isCurrentPage,
                    activeGridState = activeGridState,
                    onWallpaperClick = onWallpaperClick,
                    onLoadMore = { onLoadMoreSection(section.key) },
                )
            }
        }
    }
}

/**
 * The scrollable pill bar: the active tab sits in a rounded
 * secondaryContainer pill, labels outside the pill mute to
 * on-surface-variant. The pill is the tab's own background (v1.0.12 fix):
 * material3 draws the TabRow indicator slot ON TOP of the tab content, so
 * the full-height pill placed there covered the active label entirely —
 * as a background it always sits behind the text, and it fades in/out
 * with the selection instead of sliding between positions.
 */
@Composable
private fun HomeTabRow(
    tabs: List<HomeTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    ScrollableTabRow(
        selectedTabIndex = selectedIndex,
        edgePadding = 16.dp,
        containerColor = Color.Transparent,
        divider = {},
        indicator = {},
        modifier =
            modifier
                .fillMaxWidth()
                .testTag("browse:home-tabs"),
    ) {
        tabs.forEachIndexed { index, tab ->
            val selected = index == selectedIndex
            val pillColor by animateColorAsState(
                targetValue =
                    if (selected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        Color.Transparent
                    },
                label = "home-tab-pill",
            )
            Tab(
                selected = selected,
                onClick = { onSelect(index) },
                text = {
                    Text(
                        text = tab.title ?: stringResource(R.string.browse_recently_applied),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                    )
                },
                selectedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier
                        .padding(horizontal = 4.dp, vertical = 7.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(pillColor)
                        .testTag("browse:home-tab:${tab.key}"),
            )
        }
    }
}

/**
 * The personal page: the wallpapers the user actually applied, newest
 * first — a flat staggered grid, no pagination (Library's history tab is
 * the full trail; this page is the shortcut).
 */
@Composable
private fun RecentlyAppliedGrid(
    wallpapers: List<Wallpaper>,
    isCurrentPage: Boolean,
    activeGridState: MutableState<LazyStaggeredGridState?>,
    onWallpaperClick: (Wallpaper) -> Unit,
) {
    val gridState = rememberSaveable(saver = LazyStaggeredGridState.Saver) { LazyStaggeredGridState() }
    LaunchedEffect(isCurrentPage) {
        if (isCurrentPage) activeGridState.value = gridState
    }

    HomeStaggeredGrid(
        state = gridState,
        modifier = Modifier.testTag("browse:recently-applied"),
    ) {
        wallpaperItems(wallpapers, onWallpaperClick)
    }
}

/**
 * One section page: the feed's own staggered grid with the same prefetch
 * pagination the carousels had — the grid asks for the next page while
 * the user is still a screen away from the end.
 */
@Composable
private fun SectionTabGrid(
    section: BrowseSectionState,
    isCurrentPage: Boolean,
    activeGridState: MutableState<LazyStaggeredGridState?>,
    onWallpaperClick: (Wallpaper) -> Unit,
    onLoadMore: () -> Unit,
) {
    val gridState = rememberSaveable(saver = LazyStaggeredGridState.Saver) { LazyStaggeredGridState() }
    LaunchedEffect(isCurrentPage) {
        if (isCurrentPage) activeGridState.value = gridState
    }

    // True once the grid scrolls within eight cards of its end.
    val closeToTheEnd by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - GRID_PREFETCH_BUFFER
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

    HomeStaggeredGrid(
        state = gridState,
        modifier = Modifier.testTag("browse:section-grid:${section.key}"),
    ) {
        if (section.isFirstLoading && section.wallpapers.isEmpty()) {
            items(count = PLACEHOLDER_COUNT, key = { "placeholder:$it" }) { index ->
                GridCardPlaceholder(tall = index % 3 == 0)
            }
        } else {
            wallpaperItems(section.wallpapers, onWallpaperClick)

            if (section.isLoadingMore || (section.error != null && !section.endReached)) {
                item(span = StaggeredGridItemSpan.FullLine, key = "footer") {
                    SectionGridFooter(
                        isLoadingMore = section.isLoadingMore,
                        error = section.error,
                        errorDetail = section.errorDetail,
                        onRetry = onLoadMore,
                    )
                }
            }

            if (section.endReached && section.wallpapers.isEmpty()) {
                item(span = StaggeredGridItemSpan.FullLine, key = "empty") {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 48.dp),
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

/** The two-column staggered grid both tab pages share. */
@Composable
private fun HomeStaggeredGrid(
    state: LazyStaggeredGridState,
    modifier: Modifier = Modifier,
    content: LazyStaggeredGridScope.() -> Unit,
) {
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(HOME_GRID_COLUMNS),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalItemSpacing = 12.dp,
        state = state,
        modifier = modifier.fillMaxSize(),
        content = content,
    )
}

/** The feed cards — one per wallpaper, keyed by provider and id. */
private fun LazyStaggeredGridScope.wallpaperItems(
    wallpapers: List<Wallpaper>,
    onWallpaperClick: (Wallpaper) -> Unit,
) {
    items(
        wallpapers,
        key = { "${it.providerId}:${it.id}" },
    ) { wallpaper ->
        WallpaperCard(
            wallpaper = wallpaper,
            onClick = { onWallpaperClick(wallpaper) },
        )
    }
}

/** Skeleton card shown while a tab's first page loads. */
@Composable
private fun GridCardPlaceholder(tall: Boolean) {
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
private fun SectionGridFooter(
    isLoadingMore: Boolean,
    error: BrowseError?,
    errorDetail: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        isLoadingMore ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = modifier.fillMaxWidth().padding(16.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            }

        error != null ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = modifier.fillMaxWidth().padding(16.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = error.message(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
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

        else -> Unit
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

private const val GRID_PREFETCH_BUFFER = 8
private const val PLACEHOLDER_COUNT = 8
private const val HOME_GRID_COLUMNS = 2
