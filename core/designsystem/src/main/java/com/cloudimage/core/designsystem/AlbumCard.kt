package com.cloudimage.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest

/**
 * One cell of an album grid (v1.1.0): the album's cover with its title
 * underneath and, when the source discloses it, a count badge naming how
 * many wallpapers the album holds — the album-paradigm analog of
 * [WallpaperCard], matching its corner radius and placeholder treatment
 * so the two grids feel like one family.
 *
 * Covers are rendered at a fixed 16:10 crop: album covers arrive in mixed
 * wallpaper ratios and a uniform cell is what turns a wall of covers into
 * the site-like album shelf the paradigm promises.
 */
@Composable
fun AlbumCard(
    title: String,
    coverUrl: String,
    wallpaperCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.album_card, title)
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
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 10f),
        ) {
            AsyncImage(
                model =
                    ImageRequest
                        .Builder(LocalContext.current)
                        .data(coverUrl)
                        .crossfade(durationMillis = 220)
                        .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
                error = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxSize(),
            )
            if (wallpaperCount > 0) {
                Surface(
                    color = Color.Black.copy(alpha = 0.55f),
                    contentColor = Color.White,
                    shape = RoundedCornerShape(topStart = 10.dp),
                    modifier = Modifier.align(Alignment.BottomEnd),
                ) {
                    Text(
                        text = pluralStringResource(R.plurals.album_wallpaper_count, wallpaperCount, wallpaperCount),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        maxLines = 1,
                        modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
                    )
                }
            }
        }
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
