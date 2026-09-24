package com.cloudimage.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
 */
@Composable
fun WallpaperCard(
    wallpaper: Wallpaper,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
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
        }
    }
}
