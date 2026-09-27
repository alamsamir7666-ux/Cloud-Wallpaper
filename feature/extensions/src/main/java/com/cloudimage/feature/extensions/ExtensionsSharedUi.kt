package com.cloudimage.feature.extensions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cloudimage.extensions.core.ExtensionStatus
import kotlin.math.abs

// Shared visual atoms for the extensions feature (v1.0.20): the Cloudstream
// -style monogram avatar, chip, and size-formatting helpers used by the
// repository browser, the repo detail catalog, and the installed list.

/** A circular initial-letter avatar — the Cloudstream row icon, on-palette. */
@Composable
internal fun MonogramAvatar(
    label: String,
    seed: String,
    modifier: Modifier = Modifier,
) {
    val (container, content) = avatarColors(seed)
    Box(
        modifier =
            modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.trim().take(1).uppercase(),
            style = MaterialTheme.typography.titleMedium,
            color = content,
        )
    }
}

/** Deterministic on-palette avatar colors — one of three container roles. */
@Composable
private fun avatarColors(seed: String): Pair<Color, Color> =
    when (abs(seed.hashCode()) % 3) {
        0 -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        1 -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    }

/** Human-readable size for a catalog row: "22 KB" / "1.2 MB"; blank for 0. */
internal fun formatSizeBytes(bytes: Long): String =
    when {
        bytes <= 0L -> ""
        bytes < 1024L * 1024L -> "%.0f KB".format(bytes / 1024f)
        else -> "%.1f MB".format(bytes / 1024f / 1024f)
    }

/** The installed row's meta line: "v1.0.0 · 22 KB" — size only when known. */
internal fun entryMetaLine(entry: com.cloudimage.extensions.core.RepoPackageEntry): String {
    val size = formatSizeBytes(entry.sizeBytes)
    return buildString {
        append("v")
        append(entry.versionName)
        if (size.isNotBlank()) {
            append(" · ")
            append(size)
        }
    }
}

/**
 * A compact status label. The text never wraps (v1.0.12 fix): when the
 * chips row ran out of width, a squeezed chip stacked its label one
 * syllable per line — a chip is always a single line, and overflow chips
 * wrap as whole chips in the FlowRow instead.
 */
@Composable
internal fun LabelChip(
    text: String,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier.padding(
                    horizontal = 8.dp,
                    vertical = 4.dp,
                ),
        )
    }
}

@Composable
internal fun StatusChip(status: ExtensionStatus) {
    val label =
        stringResource(
            when (status) {
                ExtensionStatus.READY -> R.string.extensions_status_ready
                ExtensionStatus.CORRUPTED -> R.string.extensions_status_corrupted
                ExtensionStatus.UNTRUSTED -> R.string.extensions_status_untrusted
            },
        )
    if (status == ExtensionStatus.READY) {
        LabelChip(
            text = label,
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    } else {
        LabelChip(
            text = label,
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

/** Dims a disabled installed row so the off state reads at a glance. */
internal fun rowAlpha(disabled: Boolean): Float = if (disabled) 0.55f else 1f
