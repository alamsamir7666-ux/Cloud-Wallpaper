package com.cloudimage.feature.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cloudimage.core.model.ContentRating
import com.cloudimage.core.model.WallpaperCategory
import com.cloudimage.core.model.WallpaperQuery
import com.cloudimage.core.model.WallpaperSorting

/**
 * Filter sheet for the browse feed: categories, purity, sorting and order as
 * chip groups. Drafts live in local state; "Apply" commits them in one shot.
 *
 * The Sketchy chip locks while the SFW-only preference is on — the toggle
 * itself lands with the settings screen in Part 7.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterSheet(
    query: WallpaperQuery,
    sfwOnly: Boolean,
    onApply: (WallpaperQuery) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember(query) { mutableStateOf(query) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
        ) {
            FilterGroupLabel(text = stringResource(R.string.browse_filter_categories))
            ChipFlowRow {
                WallpaperCategory.entries.forEach { category ->
                    val label =
                        when (category) {
                            WallpaperCategory.GENERAL -> stringResource(R.string.browse_category_general)
                            WallpaperCategory.ANIME -> stringResource(R.string.browse_category_anime)
                            WallpaperCategory.PEOPLE -> stringResource(R.string.browse_category_people)
                        }
                    FilterChip(
                        selected = category in draft.categories,
                        onClick = { draft = draft.toggleCategory(category) },
                        label = { Text(label) },
                    )
                }
            }

            FilterGroupLabel(text = stringResource(R.string.browse_filter_content))
            ChipFlowRow {
                FilterChip(
                    selected = ContentRating.SFW in draft.contentRatings,
                    onClick = { draft = draft.toggleRating(ContentRating.SFW) },
                    label = { Text(stringResource(R.string.browse_rating_sfw)) },
                )
                FilterChip(
                    selected = !sfwOnly && ContentRating.SKETCHY in draft.contentRatings,
                    enabled = !sfwOnly,
                    onClick = { draft = draft.toggleRating(ContentRating.SKETCHY) },
                    label = { Text(stringResource(R.string.browse_rating_sketchy)) },
                )
            }
            if (sfwOnly) {
                Text(
                    text = stringResource(R.string.browse_rating_sketchy_locked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            FilterGroupLabel(text = stringResource(R.string.browse_filter_sorting))
            ChipFlowRow {
                FilterChip(
                    selected = draft.sorting == WallpaperSorting.TOPLIST,
                    onClick = { draft = draft.copy(sorting = WallpaperSorting.TOPLIST) },
                    label = { Text(stringResource(R.string.browse_sorting_toplist)) },
                )
                FilterChip(
                    selected = draft.sorting == WallpaperSorting.DATE,
                    onClick = { draft = draft.copy(sorting = WallpaperSorting.DATE) },
                    label = { Text(stringResource(R.string.browse_sorting_date)) },
                )
                FilterChip(
                    selected = draft.sorting == WallpaperSorting.RANDOM,
                    onClick = { draft = draft.copy(sorting = WallpaperSorting.RANDOM) },
                    label = { Text(stringResource(R.string.browse_sorting_random)) },
                )
                FilterChip(
                    selected = draft.sorting == WallpaperSorting.RELEVANCE,
                    onClick = { draft = draft.copy(sorting = WallpaperSorting.RELEVANCE) },
                    label = { Text(stringResource(R.string.browse_sorting_relevance)) },
                )
            }

            FilterGroupLabel(text = stringResource(R.string.browse_filter_order))
            ChipFlowRow {
                FilterChip(
                    selected = draft.descending,
                    onClick = { draft = draft.copy(descending = true) },
                    label = { Text(stringResource(R.string.browse_order_descending)) },
                )
                FilterChip(
                    selected = !draft.descending,
                    onClick = { draft = draft.copy(descending = false) },
                    label = { Text(stringResource(R.string.browse_order_ascending)) },
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) {
                TextButton(
                    onClick = { draft = WallpaperQuery() },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.browse_filter_reset))
                }
                Button(
                    onClick = { onApply(draft) },
                    modifier = Modifier.weight(2f),
                ) {
                    Text(stringResource(R.string.browse_filter_apply))
                }
            }
        }
    }
}

@Composable
private fun FilterGroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipFlowRow(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
        content()
    }
}

private fun WallpaperQuery.toggleCategory(category: WallpaperCategory): WallpaperQuery =
    copy(categories = if (category in categories) categories - category else categories + category)

private fun WallpaperQuery.toggleRating(rating: ContentRating): WallpaperQuery =
    copy(contentRatings = if (rating in contentRatings) contentRatings - rating else contentRatings + rating)
