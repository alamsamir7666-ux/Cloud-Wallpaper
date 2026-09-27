package com.cloudimage.core.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * One tab slot's horizontal extent in the bar's content space (px),
 * captured during layout and interpolated between while a swipe runs.
 */
@Immutable
internal data class TabSlot(
    val left: Float,
    val width: Float,
)

/**
 * The pill geometry for a swipe fraction: the position and the width both
 * lerp between the slot the pager is leaving and the slot it is arriving
 * at, so at 50% swipe progress the pill sits halfway between the two tabs
 * and has already adopted half of the target's width. [fraction] is
 * `currentPage + currentPageOffsetFraction` from a
 * [androidx.compose.foundation.pager.PagerState] — clamped to the slot
 * range, so cancelled swipes simply run the same math backwards as the
 * pager settles home.
 */
internal fun pillGeometry(
    slots: List<TabSlot>,
    fraction: Float,
): TabSlot {
    if (slots.isEmpty()) return TabSlot(left = 0f, width = 0f)
    val lastIndex = slots.lastIndex
    val clamped = fraction.coerceIn(0f, lastIndex.toFloat())
    val from = floor(clamped).toInt()
    val to = if (from == lastIndex) from else from + 1
    val progress = clamped - from
    return TabSlot(
        left = lerp(slots[from].left, slots[to].left, progress),
        width = lerp(slots[from].width, slots[to].width, progress),
    )
}

/**
 * The scroll position that keeps [slot] inside the bar's viewport with
 * [margin] of breathing room: unchanged while the pill is comfortably
 * visible, nudged back or forward only when it crowds an edge, and always
 * clamped to the scroll range. A zero viewport (before the first measure)
 * is a no-op so the bar never jumps on first composition.
 */
internal fun keepInViewScroll(
    viewport: Int,
    maxScroll: Int,
    current: Int,
    slot: TabSlot,
    margin: Float,
): Int {
    if (viewport <= 0) return current
    val leftInViewport = slot.left - current
    val rightInViewport = leftInViewport + slot.width
    return when {
        leftInViewport < margin -> (slot.left - margin).roundToInt()
        rightInViewport > viewport - margin -> (slot.left + slot.width - viewport + margin).roundToInt()
        else -> current
    }.coerceIn(0, maxScroll)
}

/**
 * The house pill tab bar (v1.0.18): a scrollable row of compact pill
 * tabs whose single active pill hugs its label — 14dp of horizontal
 * padding and a 32dp pill height, no minimum tab width stretching short
 * labels the way [androidx.compose.material3.ScrollableTabRow]'s 90dp
 * floor did.
 *
 * The pill is one shared element drawn behind the labels, and it tracks
 * [pageFraction] in real time: while the user drags a pager from one page
 * to the next, the pill slides and resizes proportionally between the two
 * tabs (halfway through the swipe it is halfway between them), and when
 * the swipe is released or cancelled it rides the pager's own settle or
 * snap-back animation to wherever it lands. No snap-at-the-end: the pill
 * is glued to the gesture, not to the selection.
 *
 * The bar also scrolls itself to follow the pill: whenever the sliding
 * pill crowds an edge, the bar shifts just enough to keep it visible with
 * a neighbor hint, both during gestures and during animated tab jumps.
 *
 * Labels keep the full 48dp touch height and expose Tab semantics; the
 * ripple is a capsule the exact shape of the pill's touch slot.
 */
@Composable
fun PillTabRow(
    tabs: List<String>,
    selectedIndex: Int,
    pageFraction: () -> Float,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    tabTestTag: ((Int) -> String)? = null,
) {
    val scrollState = rememberScrollState()
    val slots =
        remember(tabs.size) {
            mutableStateListOf<TabSlot>().apply {
                repeat(tabs.size) { add(TabSlot(left = 0f, width = 0f)) }
            }
        }
    val viewportWidthPx = remember { mutableIntStateOf(0) }
    val keepInViewMarginPx = with(LocalDensity.current) { KeepInViewMargin.toPx() }

    // Follow the pill: whenever pager-driven movement crowds the sliding
    // geometry against an edge of the viewport, nudge the bar just enough
    // to keep it visible with a neighbor hint. Hard-following the fraction
    // like this inherits the pager's animation curves for free — finger
    // drags track 1:1 and animated settles glide. A manual drag of the bar
    // itself is deliberately left alone: the user may browse the distant
    // tabs freely until the next page change (or settle) recovers the pill.
    LaunchedEffect(slots, viewportWidthPx) {
        var lastFraction = Float.NEGATIVE_INFINITY
        snapshotFlow {
            val fraction = pageFraction()
            pillGeometry(slots, fraction) to fraction
        }.collect { (geometry, fraction) ->
            if (fraction != lastFraction) {
                lastFraction = fraction
                val current = scrollState.value
                val desired =
                    keepInViewScroll(
                        viewport = viewportWidthPx.intValue,
                        maxScroll = scrollState.maxValue,
                        current = current,
                        slot = geometry,
                        margin = keepInViewMarginPx,
                    )
                if (desired != current) {
                    scrollState.dispatchRawDelta((desired - current).toFloat())
                }
            }
        }
    }

    Layout(
        content = {
            // The sliding pill, measured every layout pass to the current
            // interpolated geometry and placed behind the labels.
            Box(
                Modifier.background(
                    MaterialTheme.colorScheme.secondaryContainer,
                    PillShape,
                ),
            )
            tabs.forEachIndexed { index, title ->
                val selected = index == selectedIndex
                val labelColor by animateColorAsState(
                    targetValue =
                        if (selected) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    label = "pill-tab-label-$index",
                )
                Box(
                    modifier =
                        Modifier
                            .onGloballyPositioned { coordinates ->
                                slots[index] =
                                    TabSlot(
                                        left = coordinates.positionInParent().x,
                                        width = coordinates.size.width.toFloat(),
                                    )
                            }.clip(PillShape)
                            .selectable(
                                selected = selected,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(),
                                role = Role.Tab,
                                onClick = { onSelect(index) },
                            ).then(
                                tabTestTag?.let { tag -> Modifier.testTag(tag(index)) } ?: Modifier,
                            ).defaultMinSize(minWidth = MinTabSlotWidth, minHeight = MinTabSlotHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        color = labelColor,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = TabLabelPadding),
                    )
                }
            }
        },
        modifier =
            modifier
                .selectableGroup()
                .onSizeChanged { size -> viewportWidthPx.intValue = size.width }
                .horizontalScroll(scrollState),
        measurePolicy = { measurables, _ ->
            val tabMeasurables = measurables.drop(1)
            if (tabMeasurables.isEmpty()) {
                layout(0, 0) {}
            } else {
                val edgePadding = TabEdgePadding.roundToPx()
                val spacing = TabSpacing.roundToPx()
                val placeables = tabMeasurables.map { it.measure(Constraints()) }
                val placedSlots = ArrayList<TabSlot>(placeables.size)
                var x = edgePadding
                placeables.forEach { placeable ->
                    placedSlots.add(TabSlot(left = x.toFloat(), width = placeable.width.toFloat()))
                    x += placeable.width + spacing
                }
                val contentWidth = x - spacing + edgePadding
                val rowHeight = maxOf(MinTabSlotHeight.roundToPx(), placeables.maxOf { it.height })

                // The page fraction is read here, in the layout phase: a
                // running swipe re-measures just this node every frame,
                // never recomposing the labels.
                val geometry = pillGeometry(placedSlots, pageFraction())
                val pillPlaceable =
                    measurables.first().measure(
                        Constraints.fixed(geometry.width.roundToInt(), PillHeight.roundToPx()),
                    )

                layout(contentWidth, rowHeight) {
                    // The pill first: placement order is z-order, so the
                    // labels always draw on top of it.
                    pillPlaceable.placeRelative(
                        geometry.left.roundToInt(),
                        (rowHeight - pillPlaceable.height) / 2,
                    )
                    var labelX = edgePadding
                    placeables.forEach { placeable ->
                        placeable.placeRelative(labelX, (rowHeight - placeable.height) / 2)
                        labelX += placeable.width + spacing
                    }
                }
            }
        },
    )
}

private val PillShape = RoundedCornerShape(percent = 50)
private val TabEdgePadding = 16.dp
private val TabSpacing = 8.dp
private val TabLabelPadding = 14.dp
private val MinTabSlotWidth = 56.dp
private val MinTabSlotHeight = 48.dp
private val PillHeight = 32.dp
private val KeepInViewMargin = 56.dp
