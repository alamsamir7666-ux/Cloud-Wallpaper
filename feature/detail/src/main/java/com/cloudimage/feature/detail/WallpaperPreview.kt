package com.cloudimage.feature.detail

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
                    .build(),
            contentDescription = stringResource(R.string.detail_preview),
            contentScale = ContentScale.Fit,
            placeholder = ColorPainter(Color(0xFF1C1B1F)),
            error = ColorPainter(Color(0xFF375D45)),
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
    }
}

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 5f
private const val DOUBLE_TAP_SCALE = 2.5f
private const val DOUBLE_TAP_EPSILON = 0.1f
