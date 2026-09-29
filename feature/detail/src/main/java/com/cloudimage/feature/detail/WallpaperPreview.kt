package com.cloudimage.feature.detail

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.cloudimage.core.model.Wallpaper
import com.cloudimage.core.network.ImageProgressRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.coil.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import java.util.Locale
import kotlin.math.roundToInt

/**
 * One page of the fullscreen viewer (the swipe-through rework):
 *
 * - the original file streams into Coil's disk cache and is then rendered
 *   through telephoto's sub-sampling: zoomed-in regions decode tiles from
 *   the full-resolution original, so pinch-zoom stays crisp exactly like a
 *   phone gallery instead of magnifying a screen-sized bitmap;
 * - while the original downloads, a blurred blow-up of the thumbnail fills
 *   the screen and a corner chip reports byte-accurate progress — but only
 *   after a grace period, so a warm page never blinks its loading
 *   furniture;
 * - a downward drag dismisses: the image translates down 1:1 with the
 *   finger and fades, and nothing scales, skews or resizes at any point;
 *   released past a threshold (or flung) the close continues on its own,
 *   otherwise everything springs back;
 * - an upward drag opens the details panel, mirroring the pill it echoes;
 * - sideways is not this page's business at all: the surrounding
 *   HorizontalPager owns left/right paging natively.
 *
 * The gesture choreography that makes the vertical drag coexist with
 * telephoto's zoomable without ever fighting it: the invisible gesture
 * layer sits BELOW the image in dispatch order — it is this Box's first
 * child while the moving image Box is its sibling on top. Compose
 * dispatches the Main pass to the topmost sibling first, so telephoto
 * sees every event before this layer does; a drag the zoomable claims
 * (a zoomed-in pan, a pinch, a quick zoom) therefore arrives here
 * already consumed, and the layer stands down the moment it sees one.
 * The layer's own consumption, in turn, is visible to the pager above —
 * ancestors dispatch after descendants — so a locked vertical drag
 * switches the pager off for the rest of the gesture. One gesture, one
 * writer, always, with no Initial-pass choreography to get wrong.
 *
 * The layer also stands down for the gestures telephoto claims WITHOUT
 * consuming: a down landing inside the double-tap window of a recent up
 * (a double-tap or a quick-zoom hold in the making) is left entirely to
 * the zoomable, and any zoom fraction at all — the double-tap zoom
 * animates without consuming a single event — disqualifies the gesture
 * before the lock ever writes.
 */
@Composable
internal fun WallpaperViewerPage(
    wallpaper: Wallpaper,
    motion: ViewerMotionState,
    onDismiss: () -> Unit,
    onOpenInfo: () -> Unit,
    onZoomedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var retryAttempt by remember { mutableIntStateOf(0) }
    var loadFailed by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val url = wallpaper.fullUrl
    val progress = ImageProgressRegistry.observe(url).collectAsState()

    // The gesture layer reads whatever the latest composition parked here —
    // its pointer input never restarts mid-drag.
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentOnOpenInfo by rememberUpdatedState(onOpenInfo)
    val currentOnZoomedChange by rememberUpdatedState(onZoomedChange)

    // Leave the registry clean for the next wallpaper.
    DisposableEffect(url) {
        onDispose { ImageProgressRegistry.reset(url) }
    }

    val zoomableState = rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = MAX_ZOOM))
    val imageState = rememberZoomableImageState(zoomableState)
    LaunchedEffect(zoomableState) {
        snapshotFlow { (zoomableState.zoomFraction ?: 0f) > ZOOMED_FRACTION }
            .distinctUntilChanged()
            .collect { currentOnZoomedChange(it) }
    }
    val imageDisplayed = imageState.isImageDisplayed

    // Loading furniture shows itself only after a grace period: a warm
    // page paints its image before the furniture could ever appear, so
    // the blurred backdrop and corner chip never blink on arrival. A
    // genuinely slow original still gets the full treatment, just
    // without the false alarm at the start.
    var showLoadingFurniture by remember { mutableStateOf(false) }
    LaunchedEffect(url, imageDisplayed) {
        if (imageDisplayed) {
            showLoadingFurniture = false
        } else {
            delay(LOADING_FURNITURE_GRACE_MS)
            if (!imageDisplayed) showLoadingFurniture = true
        }
    }
    val backdropAlpha by animateFloatAsState(
        targetValue = if (showLoadingFurniture) 1f else 0f,
        animationSpec = tween(durationMillis = 350),
        label = "backdrop-alpha",
    )

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .onSizeChanged { size -> motion.updateViewport(size.width.toFloat(), size.height.toFloat()) }
                // The scrim: solid black at rest, fading in step with the
                // dismiss drag so the whole viewer dissolves toward the
                // window behind it as the image travels down.
                .drawBehind { drawRect(color = Color.Black, alpha = motion.contentAlpha) },
    ) {
        // The gesture layer — this Box's FIRST child, i.e. the BOTTOM
        // sibling in dispatch order. Invisible, stationary (never inside
        // the moving Box, whose offset would feed the drag's own writes
        // back into the pointer coordinates), and below the image: see
        // the choreography note above.
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalDragGestures(
                        motion = motion,
                        zoomFraction = { zoomableState.zoomFraction ?: 0f },
                        onDismiss = { currentOnDismiss() },
                        onOpenInfo = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            currentOnOpenInfo()
                        },
                    ),
        )

        // Everything the finger moves travels together — the backdrop, the
        // image and its loading furniture — on whole pixels, through the
        // LAYOUT (a sub-pixel layer translation would re-sample the
        // sub-sampled tiles at a fresh fractional position every frame,
        // which reads as a faint shimmer along the drag axis). The fade
        // stays a draw-phase concern. Translation and alpha are the only
        // two effects the dismiss ever applies — never a scale.
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .offset { IntOffset(x = 0, y = motion.offsetY.floatValue.roundToInt()) }
                    .graphicsLayer { alpha = motion.contentAlpha },
        ) {
            // Blurred thumbnail backdrop: something rich fills the screen
            // while (and only while) the original is on its way.
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

            ZoomableAsyncImage(
                model =
                    ImageRequest
                        .Builder(context)
                        .data(wallpaper.fullUrl)
                        // No crossfade on purpose: the pager's own page
                        // motion is the transition, and a fade from
                        // transparent over the black scrim reads as a
                        // blink on every swipe.
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

            // Corner loading chip: how much of the original has arrived.
            if (showLoadingFurniture && !loadFailed) {
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
}

/**
 * The vertical half of the viewer's gestures as a modifier: a dominant
 * downward drag dismisses (1:1 translate plus fade, no scaling ever) and
 * a dominant upward one opens details. This layer is passive by
 * construction — it runs BELOW the zoomable image in the Main-pass
 * dispatch order, so it only ever claims a drag the image demonstrably
 * ignored, and it consumes nothing until its lock has been taken past
 * the slop:
 *
 * - a second pointer (pinch intent) stands it down at once;
 * - a move the image claimed (zoomed pan, pinch, quick zoom) arrives
 *   consumed and stands it down at once;
 * - any zoom fraction at all — the double-tap zoom animates without
 *   consuming events — disqualifies the gesture, checked before every
 *   lock and again before every write;
 * - a down inside the double-tap window of a recent up is skipped
 *   outright: that finger belongs to telephoto's double-tap / quick-zoom
 *   detectors, which is exactly the interleaving the old arbiter fought;
 * - a drag that leans sideways past the slop yields the whole gesture —
 *   the surrounding pager owns horizontal movement, and the layer's
 *   locked moves are consumed so the pager stands down for it instead.
 *
 * Real taps, double-tap zoom, quick zoom and pinches are untouched — the
 * layer never consumes downs or ups, and by the time its 10dp lock
 * engages, the image's own tap detectors (platform slop is smaller) have
 * already given up on the gesture being a tap.
 */
private fun Modifier.verticalDragGestures(
    motion: ViewerMotionState,
    zoomFraction: () -> Float,
    onDismiss: () -> Unit,
    onOpenInfo: () -> Unit,
): Modifier =
    pointerInput(motion) {
        val lockSlopPx = LOCK_SLOP.toPx()
        val dismissFlingPx = DISMISS_FLING_VELOCITY.toPx()
        val detailsCommitPx = DETAILS_COMMIT_DISTANCE.toPx()
        val detailsFlingPx = DETAILS_FLING_VELOCITY.toPx()
        val detailsNudgeCapPx = DETAILS_NUDGE_CAP.toPx()
        val doubleTapWindowMs = viewConfiguration.doubleTapTimeoutMillis.toLong()
        val doubleTapRadiusPx = DOUBLE_TAP_RADIUS.toPx()
        val velocityTracker = VelocityTracker()

        // The up that ended the previous gesture, watched for the window
        // inside which a new down is really the second half of a
        // double-tap or a quick-zoom hold — telephoto's finger, not ours.
        var lastUpMillis = 0L
        var lastUpX = 0f
        var lastUpY = 0f

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val arbiter =
                VerticalDragArbiter(
                    lockSlopPx = lockSlopPx,
                    dismissCommitPx = motion.dismissDistancePx,
                    dismissFlingPx = dismissFlingPx,
                    detailsCommitPx = detailsCommitPx,
                    detailsFlingPx = detailsFlingPx,
                )
            arbiter.onDown(down.position.x, down.position.y)
            velocityTracker.resetTracking()
            velocityTracker.addPosition(down.uptimeMillis, down.position)

            val withinDoubleTapWindow =
                down.uptimeMillis - lastUpMillis <= doubleTapWindowMs &&
                    squaredDistance(down.position.x, down.position.y, lastUpX, lastUpY) <=
                    doubleTapRadiusPx * doubleTapRadiusPx

            // The dismiss exit is untouchable: it finishes in its own
            // 220ms and the screen pops with it.
            var tracking =
                !withinDoubleTapWindow &&
                    !motion.dismissing.value &&
                    zoomFraction() <= ZOOM_STAND_DOWN_FRACTION

            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.size > 1) {
                    // A second finger is pinch intent: the zoomable takes
                    // the rest of this gesture.
                    if (tracking) {
                        tracking = false
                        motion.animateRestore()
                    }
                    continue
                }
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    lastUpMillis = change.uptimeMillis
                    lastUpX = change.position.x
                    lastUpY = change.position.y
                    if (tracking) {
                        val velocity = velocityTracker.calculateVelocity()
                        when (arbiter.onUp(velocity.y)) {
                            VerticalRelease.DismissViewer -> motion.animateDismiss(onDismiss)

                            VerticalRelease.ShowDetails -> {
                                onOpenInfo()
                                motion.animateRestore()
                            }

                            VerticalRelease.RestorePosition -> motion.animateRestore()
                        }
                    }
                    break
                }
                velocityTracker.addPosition(change.uptimeMillis, change.position)
                if (!tracking) continue
                if (change.isConsumed || zoomFraction() > ZOOM_STAND_DOWN_FRACTION) {
                    // The image's own gesture layer claimed this drag
                    // mid-flight (a zoomed pan, a pinch taking shape) —
                    // settle home once and stay out of it.
                    tracking = false
                    motion.animateRestore()
                    continue
                }

                when (arbiter.onMove(change.position.x, change.position.y)) {
                    VerticalGesture.DISMISS, VerticalGesture.DETAILS -> {
                        // The first locked write takes over from whatever
                        // the offset is right now (a settle animation in
                        // flight stops dead here), so the follow is
                        // continuous with the image on screen — no snap.
                        motion.cancelAnimations()
                        motion.offsetY.floatValue =
                            arbiter.followY(
                                currentOffsetY = motion.offsetY.floatValue,
                                detailsNudgeCapPx = detailsNudgeCapPx,
                            )
                        // The pager above sees this consumption on its own
                        // Main dispatch: a locked vertical drag switches
                        // horizontal paging off for the rest of the gesture.
                        change.consume()
                    }

                    VerticalGesture.YIELDED, VerticalGesture.NONE -> Unit
                }
            }
        }
    }

/** Squared euclidean distance between two points. */
private fun squaredDistance(
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
): Float {
    val dx = x1 - x2
    val dy = y1 - y2
    return dx * dx + dy * dy
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

/**
 * Any zoom at all mid-gesture means the zoomable took the finger (the
 * double-tap listener animates without consuming events) — the gesture
 * layer stands down the moment the fraction leaves zero.
 */
private const val ZOOM_STAND_DOWN_FRACTION = 0.001f

private val BACKDROP_BLUR_RADIUS = 24.dp
private val PROGRESS_CHIP_TOP_PADDING = 60.dp
private const val MIB = 1024.0

/** Zoom ceiling for the preview, relative to the image's native size. */
private const val MAX_ZOOM = 5f

/** Movement (either axis) after which the drag locks to its dominant axis. */
private val LOCK_SLOP = 10.dp

/** A downward fling this fast dismisses even without the distance. */
private val DISMISS_FLING_VELOCITY = 1250.dp

/** Upward travel that opens the details panel on release — the pill's own. */
private val DETAILS_COMMIT_DISTANCE = 64.dp
private val DETAILS_FLING_VELOCITY = 800.dp

/** How far the details nudge may lift the image while the finger drags up. */
private val DETAILS_NUDGE_CAP = 24.dp

/**
 * A down within this radius of the previous up, inside the double-tap
 * timeout, is treated as the second half of a double-tap / quick-zoom
 * and left entirely to the zoomable.
 */
private val DOUBLE_TAP_RADIUS = 64.dp

/** Grace before the blurred backdrop and progress chip show themselves. */
private const val LOADING_FURNITURE_GRACE_MS = 150L
