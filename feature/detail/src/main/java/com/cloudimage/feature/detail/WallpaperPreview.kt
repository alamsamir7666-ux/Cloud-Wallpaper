package com.cloudimage.feature.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.cloudimage.core.model.Wallpaper

/**
 * Fullscreen, zoomable wallpaper preview: pinch to zoom between 1x and 5x,
 * pan while zoomed in, double-tap to toggle between 1x and a comfortable
 * reading zoom. The gesture handlers sit on the container and transform the
 * image via a graphics layer, so nothing recomposes while panning.
 */
@Composable
fun ZoomableWallpaperPreview(
    wallpaper: Wallpaper,
    modifier: Modifier = Modifier,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var retryAttempt by remember { mutableIntStateOf(0) }
    var loadFailed by remember { mutableStateOf(false) }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .clipToBounds()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                        // Only pan while there is zoom to move around in;
                        // releasing the pinch at 1x recenters the image.
                        offset = if (newScale > 1f) offset + pan else Offset.Zero
                        scale = newScale
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1f + DOUBLE_TAP_EPSILON) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = DOUBLE_TAP_SCALE
                            }
                        },
                    )
                },
    ) {
        AsyncImage(
            model =
                ImageRequest.Builder(LocalContext.current)
                    .data(wallpaper.fullUrl)
                    .crossfade(durationMillis = 250)
                    // Bumping the attempt re-executes the request; the
                    // memoryCacheKey changes with it so retries re-fetch.
                    .setParameter("retry", retryAttempt, memoryCacheKey = "retry-$retryAttempt")
                    .build(),
            contentDescription = stringResource(R.string.detail_preview),
            contentScale = ContentScale.Fit,
            placeholder = ColorPainter(Color(0xFF1C1B1F)),
            error = ColorPainter(Color(0xFF375D45)),
            onError = { loadFailed = true },
            onSuccess = { loadFailed = false },
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
        )

        if (loadFailed) {
            RetryOverlay(
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .padding(16.dp),
                onRetry = {
                    loadFailed = false
                    retryAttempt += 1
                },
            )
        }
    }
}

@Composable
private fun RetryOverlay(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        modifier =
            modifier
                .clickable(onClick = onRetry),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(
                text = stringResource(R.string.detail_image_retry),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 5f
private const val DOUBLE_TAP_SCALE = 2.5f
private const val DOUBLE_TAP_EPSILON = 0.1f
