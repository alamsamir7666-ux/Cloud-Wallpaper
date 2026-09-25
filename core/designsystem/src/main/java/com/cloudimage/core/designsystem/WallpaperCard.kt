package com.cloudimage.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.cloudimage.core.model.Wallpaper

/**
 * One cell of a staggered grid: a Coil image that reserves space from the
 * wallpaper's real aspect ratio, so the grid never jumps when pixels land.
 *
 * Shared by the browse feed and the favorites grid so both masonry views
 * look and behave identically.
 *
 * Ratios are clamped to a sane band — providers host 21:9 panoramas and
 * 9:16 phone shots alike, and an unclamped cell would dwarf the others.
 *
 * [providerLabel] (v1.0.9) overlays the source's name on the merged feed —
 * CloudStream's per-result `apiName` analog — so provenance stays visible
 * wherever rows from different sources interleave. Null (the default)
 * renders no overlay.
 */
@Composable
fun WallpaperCard(
    wallpaper: Wallpaper,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    providerLabel: String? = null,
) {
    val description = stringResource(R.string.wallpaper_card, wallpaper.id)
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .semantics { contentDescription = description },
    ) {
        Box(
            modifier =
                Modifier.fillMaxWidth().aspectRatio(
                    wallpaper.aspectRatio?.coerceIn(minimumValue = 0.5f, maximumValue = 2.4f)
                        ?: 1.4f,
                ),
        ) {
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
            if (providerLabel != null) {
                Surface(
                    color = Color.Black.copy(alpha = 0.55f),
                    contentColor = Color.White,
                    shape = RoundedCornerShape(topEnd = 10.dp),
                    modifier = Modifier.align(Alignment.BottomStart),
                ) {
                    Text(
                        text = providerLabel,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
                    )
                }
            }
        }
    }
}
