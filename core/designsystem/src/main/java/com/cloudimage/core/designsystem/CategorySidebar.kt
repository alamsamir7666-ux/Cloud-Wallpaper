package com.cloudimage.core.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One row of the category sidebar (v1.1.0): dumb data by design — the
 * browse feature translates its source-side categories into these, so the
 * design system stays free of data-layer types exactly like
 * [PillTabRow] takes plain strings.
 */
data class CategorySidebarEntry(
    val id: String,
    val label: String,
    val iconEmoji: String? = null,
)

/**
 * The album paradigm's category sidebar (v1.1.0) — a Realme Smart
 * Sidebar-styled overlay: a dark translucent floating panel on the right
 * edge listing the source's categories vertically, each as an icon circle
 * with its name underneath, over a dimmed scrim that dismisses on tap.
 *
 * The dark glass is deliberate and theme-independent, the way Realme's
 * own sidebar keeps its night look in light mode: an overlay gadget reads
 * as chrome, not content. The panel slides in from the right edge it
 * clings to; the caller keeps [CategorySidebarHandle] floating on the
 * edge while collapsed.
 *
 * [expanded] drives both layers; [onDismissRequest] fires on the scrim
 * tap. The system back gesture is the caller's to wire (the browse
 * screen closes the sidebar before it unwinds its scope stack).
 */
@Composable
fun CategorySidebar(
    expanded: Boolean,
    entries: List<CategorySidebarEntry>,
    selectedCategoryId: String?,
    onCategorySelected: (CategorySidebarEntry) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The scrim dims the feed behind the panel and closes the sidebar on
    // tap — Realme's outside-touch dismissal.
    AnimatedVisibility(
        visible = expanded,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(150)),
        modifier = modifier,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.40f))
                    .clickable(onClick = onDismissRequest),
        )
    }

    // The panel itself: a tall dark-glass pill floating off the right
    // edge, categories stacked in one scrollable column.
    AnimatedVisibility(
        visible = expanded,
        enter =
            slideInHorizontally(tween(220)) { it } +
                fadeIn(tween(180)),
        exit =
            slideOutHorizontally(tween(200)) { it } +
                fadeOut(tween(150)),
        modifier = modifier,
    ) {
        Box(
            contentAlignment = Alignment.CenterEnd,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier =
                    Modifier
                        .padding(end = SIDEBAR_EDGE_MARGIN)
                        .fillMaxHeight(SIDEBAR_HEIGHT_FRACTION)
                        .width(SIDEBAR_WIDTH)
                        .background(PANEL_COLOR, RoundedCornerShape(24.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(24.dp)),
            ) {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding =
                        androidx.compose.foundation.layout
                            .PaddingValues(vertical = 14.dp, horizontal = 8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(entries, key = { it.id }) { entry ->
                        CategorySidebarItem(
                            entry = entry,
                            selected = entry.id == selectedCategoryId,
                            onClick = { onCategorySelected(entry) },
                        )
                    }
                }
            }
        }
    }
}

/** One category: the icon circle with its name underneath. */
@Composable
private fun CategorySidebarItem(
    entry: CategorySidebarEntry,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.category_sidebar_entry, entry.label)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            modifier
                .clickable(onClick = onClick)
                .semantics { contentDescription = description },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(48.dp)
                    .background(
                        if (selected) {
                            Color.White.copy(alpha = 0.30f)
                        } else {
                            Color.White.copy(alpha = 0.13f)
                        },
                        CircleShape,
                    ),
        ) {
            Text(
                // A source without an emoji falls back to the label's
                // first letter — every category stays identifiable.
                text = entry.iconEmoji ?: entry.label.take(1).uppercase(),
                fontSize = 20.sp,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
        Text(
            text = entry.label,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            color = Color.White.copy(alpha = 0.92f),
            textAlign = TextAlign.Center,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
        )
    }
}

/**
 * The collapsed sidebar's edge handle (v1.1.0): a small translucent tab
 * hugging the right edge, vertically centered — tap opens the sidebar.
 * Stays visible (and faint) even while the panel is open, so the edge
 * keeps its affordance; the caller hides it if it prefers otherwise.
 */
@Composable
fun CategorySidebarHandle(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.category_sidebar_open)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .size(width = 36.dp, height = 72.dp)
                .background(
                    Color.Black.copy(alpha = 0.55f),
                    RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp),
                ).clickable(onClick = onClick)
                .semantics { contentDescription = description },
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
            contentDescription = null,
            tint = Color.White,
        )
    }
}

/** The panel's dark glass — theme-independent by design (see KDoc). */
private val PANEL_COLOR = Color(0xE6161A20)

private val SIDEBAR_WIDTH = 84.dp
private val SIDEBAR_EDGE_MARGIN = 10.dp
private const val SIDEBAR_HEIGHT_FRACTION = 0.66f
