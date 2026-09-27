package com.cloudimage.feature.detail

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.cloudimage.core.model.Wallpaper
import com.cloudimage.core.network.ImageProgressRegistry
import kotlinx.coroutines.flow.filter
import me.saket.telephoto.ExperimentalTelephotoApi
import me.saket.telephoto.flick.FlickToDismiss
import me.saket.telephoto.flick.FlickToDismissState
import me.saket.telephoto.flick.rememberFlickToDismissState
import me.saket.telephoto.zoomable.ZoomableImageState
import me.saket.telephoto.zoomable.coil.ZoomableAsyncImage
import java.util.Locale

/**
 * Fullscreen wallpaper preview, gallery-grade (v1.0.17):
 *
 * - the original file streams into Coil's disk cache and is then rendered
 *   through telephoto's sub-sampling: zoomed-in regions decode tiles from
 *   the full-resolution original, so pinch-zoom stays crisp exactly like a
 *   phone gallery instead of magnifying a screen-sized bitmap;
 * - while the original downloads, a blurred blow-up of the thumbnail fills
 *   the screen and a corner chip reports byte-accurate progress
 *   ("47%", or "1.2 MB" when the server declares no length);
 * - a quick downward flick anywhere on the image dismisses the screen —
 *   disabled while zoomed so panning always wins.
 *
 * The zoomable state is created by the caller and hoisted in so the
 * surrounding chrome (action bar, "More like this") can react to zoom.
 */
@OptIn(ExperimentalTelephotoApi::class)
@Composable
fun ZoomableWallpaperPreview(
    wallpaper: Wallpaper,
    imageState: ZoomableImageState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var retryAttempt by remember { mutableIntStateOf(0) }
    var loadFailed by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val url = wallpaper.fullUrl
    val progress = ImageProgressRegistry.observe(url).collectAsState()
    val flickState = rememberFlickToDismissState()

    // Flick's spring settles out of view -> this screen pops.
    LaunchedEffect(flickState) {
        snapshotFlow { flickState.gestureState }
            .filter { it is FlickToDismissState.GestureState.Dismissed }
            .collect { onDismiss() }
    }

    // Leave the registry clean for the next wallpaper.
    DisposableEffect(url) {
        onDispose { ImageProgressRegistry.reset(url) }
    }

    val imageDisplayed = imageState.isImageDisplayed
    val backdropAlpha by animateFloatAsState(
        targetValue = if (imageDisplayed) 0f else 1f,
        animationSpec = tween(durationMillis = 350),
        label = "backdrop-alpha",
    )

    Box(modifier = modifier.fillMaxSize()) {
        // Blurred thumbnail backdrop: something rich fills the screen while
        // (and only while) the original is on its way.
        if (backdropAlpha > 0.01f) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = backdropAlpha },
            ) {
                AsyncImage(
                    model =
                        ImageRequest
                            .Builder(context)
                            .data(wallpaper.thumbUrl)
                            .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .blur(BACKDROP_BLUR_RADIUS),
                )
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.35f)),
                )
            }
        }

        // Flick-to-dismiss only when the image rests at its base size; any
        // zoom in, and panning owns the gesture.
        val atBaseZoom = (imageState.zoomableState.zoomFraction ?: 0f) < ZOOMED_FRACTION
        FlickToDismiss(
            state = flickState,
            enabled = atBaseZoom,
        ) {
            ZoomableAsyncImage(
                model =
                    ImageRequest
                        .Builder(context)
                        .data(wallpaper.fullUrl)
                        .crossfade(durationMillis = 250)
                        // Bumping the attempt re-executes the request; the
                        // memoryCacheKey changes with it so retries re-fetch.
                        .setParameter("retry", retryAttempt, memoryCacheKey = "retry-$retryAttempt")
                        .listener(
                            onError = { _, _ -> loadFailed = true },
                            onSuccess = { _, _ ->
                                loadFailed = false
                                ImageProgressRegistry.finish(url)
                            },
                        ).build(),
                contentDescription = stringResource(R.string.detail_preview),
                state = imageState,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Corner loading chip: how much of the original has arrived.
        if (!imageDisplayed && !loadFailed) {
            ProgressChip(
                progress = progress.value,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(top = PROGRESS_CHIP_TOP_PADDING, end = 16.dp),
            )
        }

        if (loadFailed) {
            RetryOverlay(
                onRetry = {
                    loadFailed = false
                    retryAttempt += 1
                },
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .padding(16.dp),
            )
        }
    }
}

/**
 * A compact glass chip reporting image download progress: a determinate
 * ring plus percent when the server declared a length, an indeterminate
 * ring plus megabytes-so-far when it did not. Announced politely to
 * accessibility as it updates.
 */
@Composable
private fun ProgressChip(
    progress: com.cloudimage.core.network.ImageProgress?,
    modifier: Modifier = Modifier,
) {
    val percent =
        progress
            ?.fraction
            ?.let { (it * 100).toInt() }
    val label =
        when {
            percent != null -> "$percent%"
            progress != null -> formatMegabytes(progress.bytesRead)
            else -> stringResource(R.string.detail_loading_image)
        }
    val description =
        if (percent != null) {
            stringResource(R.string.detail_loading_image_percent, percent)
        } else {
            stringResource(R.string.detail_loading_image)
        }

    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = Color.Black.copy(alpha = 0.55f),
        modifier =
            modifier.semantics {
                contentDescription = description
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 7.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        ) {
            if (percent != null) {
                CircularProgressIndicator(
                    progress = { percent / 100f },
                    strokeWidth = 2.dp,
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.25f),
                    modifier = Modifier.size(14.dp),
                )
            } else {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.25f),
                    modifier = Modifier.size(14.dp),
                )
            }
            Spacer(Modifier.width(7.dp))
            Text(
                text = label,
                color = Color.White.copy(alpha = 0.92f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
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

/** Megabytes rounded to one decimal, floor'd at 0.1 so the chip never reads "0.0 MB". */
private fun formatMegabytes(bytes: Long): String = String.format(Locale.US, "%.1f MB", (bytes / MIB).coerceAtLeast(0.1))

/** Fraction of the zoom range past which the surrounding chrome steps aside. */
internal const val ZOOMED_FRACTION = 0.04f

private val BACKDROP_BLUR_RADIUS = 24.dp
private val PROGRESS_CHIP_TOP_PADDING = 60.dp
private const val MIB = 1024.0
