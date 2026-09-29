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
import androidx.compose.runtime.MutableState
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
import androidx.compose.ui.input.pointer.PointerEventPass
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
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.cloudimage.core.model.Wallpaper
import com.cloudimage.core.network.ImageProgressRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.coil.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import java.util.Locale
import kotlin.math.abs

/**
 * Fullscreen wallpaper preview, gallery-grade (v1.0.17, reworked v1.0.23):
 *
 * - the original file streams into Coil's disk cache and is then rendered
 *   through telephoto's sub-sampling: zoomed-in regions decode tiles from
 *   the full-resolution original, so pinch-zoom stays crisp exactly like a
 *   phone gallery instead of magnifying a screen-sized bitmap;
 * - while the original downloads, a blurred blow-up of the thumbnail fills
 *   the screen and a corner chip reports byte-accurate progress;
 * - every swipe direction does exactly one thing (v1.0.23). A drag locks to
 *   its dominant axis past a small slop and never changes its mind:
 *
 *     * swipe DOWN dismisses — the image follows the finger at 1:1 and
 *       fades, the scrim fades with it, and nothing ever scales, skews or
 *       resizes. Released past a third of the screen (or flung fast
 *       enough), the close continues on its own; released earlier,
 *       everything springs back;
 *     * swipe LEFT / RIGHT pages to the next / previous wallpaper of the
 *       list the viewer was opened from — the gallery feed, a library
 *       tab, or the "More like this" row;
 *     * swipe UP opens the details panel, like the pill it mirrors.
 *
 * - the zoomable states are keyed by wallpaper, so paging resets the zoom
 *   without touching the drag offsets hoisted in [motion] — an in-flight
 *   page animation finishes across the swap;
 * - paging never trades the old image for a blank (v1.0.24): the neighbor a
 *   swipe points at warms up offscreen through the same image stack, the
 *   release holds until it can paint, and the loading furniture (blurred
 *   backdrop, progress chip) only shows after a grace period instead of
 *   blinking on every swap;
 * - zoomed in, panning owns the finger and none of the above fire.
 *
 * [zoomedOut] and [imageDisplayed] report the zoom and load state upward
 * so the surrounding chrome (action bar, carousel) can react without
 * owning the states; [motion] carries the live offsets back down.
 */
@Composable
internal fun ZoomableWallpaperPreview(
    wallpaper: Wallpaper,
    zoomedOut: MutableState<Boolean>,
    imageDisplayed: MutableState<Boolean>,
    motion: ViewerMotionState,
    onDismiss: () -> Unit,
    onNavigate: (Int) -> Unit,
    onOpenInfo: () -> Unit,
    hasNext: Boolean,
    hasPrevious: Boolean,
    navigateTarget: (Int) -> Wallpaper?,
    modifier: Modifier = Modifier,
) {
    var retryAttempt by remember { mutableIntStateOf(0) }
    var loadFailed by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val url = wallpaper.fullUrl
    val progress = ImageProgressRegistry.observe(url).collectAsState()

    // The gesture layer reads whatever the latest composition parked here —
    // its pointer input never restarts mid-drag, so the neighbors arriving
    // late (the lookalike fallback) are picked up without a hiccup.
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentOnNavigate by rememberUpdatedState(onNavigate)
    val currentOnOpenInfo by rememberUpdatedState(onOpenInfo)
    val currentHasNext by rememberUpdatedState(hasNext)
    val currentHasPrevious by rememberUpdatedState(hasPrevious)
    val currentNavigateTarget by rememberUpdatedState(navigateTarget)

    // The readiness gate (v1.0.24): the neighbor a sideways drag points at
    // warms up offscreen, and the page transition waits for it to paint —
    // see [ViewerPreloadGate].
    val preloadGate = remember { ViewerPreloadGate() }

    // Leave the registry clean for the next wallpaper.
    DisposableEffect(url) {
        onDispose { ImageProgressRegistry.reset(url) }
    }

    val zoomableState = rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = MAX_ZOOM))
    val imageState = rememberZoomableImageState(zoomableState)
    LaunchedEffect(zoomableState) {
        snapshotFlow { (zoomableState.zoomFraction ?: 0f) > ZOOMED_FRACTION }
            .collect { zoomedOut.value = it }
    }
    LaunchedEffect(imageState) {
        snapshotFlow { imageState.isImageDisplayed }.collect { imageDisplayed.value = it }
    }

    // Loading furniture shows itself only after a grace period (v1.0.24):
    // a warm swap paints the incoming image before the furniture could
    // ever appear, which is what used to flash — the blurred backdrop
    // pulsing in and out and the corner chip blinking — on every page turn.
    // A genuinely slow original still gets the full treatment, just
    // without the false alarm at the start.
    var showLoadingFurniture by remember { mutableStateOf(false) }
    LaunchedEffect(url, imageDisplayed.value) {
        if (imageDisplayed.value) {
            showLoadingFurniture = false
        } else {
            delay(LOADING_FURNITURE_GRACE_MS)
            if (!imageDisplayed.value) showLoadingFurniture = true
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
                .onSizeChanged { size ->
                    motion.updateViewport(size.width.toFloat(), size.height.toFloat())
                }
                // The scrim: solid black at rest, fading toward
                // transparent in step with the dismiss drag.
                .drawBehind { drawRect(color = Color.Black, alpha = 1f - motion.dismissProgress) },
    ) {
        // The offscreen warmer: a sideways drag points at the neighbor it
        // would page to, and that one loads here, beneath everything,
        // through the same image stack that will show it. The page
        // transition waits for this to paint, so the swap never trades
        // the old image for a blank beat (v1.0.24).
        preloadGate.armed.value?.let { warming ->
            PreloadedViewerImage(wallpaper = warming, gate = preloadGate)
        }

        // Everything the finger moves travels together — the backdrop, the
        // image and its loading furniture — with the offsets and fade read
        // at draw time so the drag itself never recomposes.
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = motion.offsetX.floatValue
                        translationY = motion.offsetY.floatValue
                        alpha = motion.imageAlpha
                    },
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

            // The gesture host. While the image rests at its base size a
            // single finger belongs to the arbiter below; zoomed in, the
            // zoomable consumes the drags and the arbiter stands down.
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .pointerInputGestures(
                            motion = motion,
                            gate = preloadGate,
                            zoomedOut = zoomedOut,
                            hasNext = { currentHasNext },
                            hasPrevious = { currentHasPrevious },
                            navigateTarget = { delta -> currentNavigateTarget(delta) },
                            onDismiss = { currentOnDismiss() },
                            onNavigate = { delta -> currentOnNavigate(delta) },
                            onOpenInfo = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                currentOnOpenInfo()
                            },
                        ),
            ) {
                ZoomableAsyncImage(
                    model =
                        ImageRequest
                            .Builder(context)
                            .data(wallpaper.fullUrl)
                            // No crossfade on purpose (v1.0.24): the page
                            // transition's motion is the enter spring, and a
                            // fade from transparent over the black scrim is
                            // exactly the blink this rework removes.
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
 * The four-direction gesture arbiter as a modifier: one dominant axis per
 * drag, locked past the slop; down dismisses (1:1 translate plus fade, no
 * scaling ever), sideways pages, up opens details. A second finger or the
 * zoomable claiming the drag stands the arbiter down and settles the
 * image back home.
 *
 * The handler lives in the Initial pass (v1.0.25). This Box is the
 * zoomable's parent, so in the Main pass its move consumption landed
 * only after the zoomable's own detectors had already dispatched — a
 * swipe's release counted as a tap there, arming telephoto's
 * double-tap window, and the next quick swipe (the normal rhythm of
 * paging) fell into it as quick zoom: the zoomable started consuming
 * the drag out from under the arbiter mid-flight. Claiming moves first
 * in Initial makes the same consumption visible to those detectors, so
 * a swipe reads as a swipe. Real taps, double-tap zoom, quick zoom and
 * pinches are untouched — the arbiter still never consumes downs, ups
 * or multi-finger events.
 */
private fun Modifier.pointerInputGestures(
    motion: ViewerMotionState,
    gate: ViewerPreloadGate,
    zoomedOut: MutableState<Boolean>,
    hasNext: () -> Boolean,
    hasPrevious: () -> Boolean,
    navigateTarget: (Int) -> Wallpaper?,
    onDismiss: () -> Unit,
    onNavigate: (Int) -> Unit,
    onOpenInfo: () -> Unit,
): Modifier =
    then(
        pointerInput(Unit) {
            val velocityTracker = VelocityTracker()
            val detailsNudgeCapPx = DETAILS_NUDGE_CAP.toPx()

            awaitEachGesture {
                val down =
                    awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial,
                    )
                val navigateCommitPx = size.width * NAVIGATE_COMMIT_FRACTION
                val arbiter =
                    ViewerGestureArbiter(
                        lockSlopPx = LOCK_SLOP.toPx(),
                        dismissCommitPx = motion.dismissDistancePx,
                        dismissFlingPx = DISMISS_FLING_VELOCITY.toPx(),
                        detailsCommitPx = DETAILS_COMMIT_DISTANCE.toPx(),
                        detailsFlingPx = DETAILS_FLING_VELOCITY.toPx(),
                        navigateCommitPx = navigateCommitPx,
                        navigateFlingPx = NAVIGATE_FLING_VELOCITY.toPx(),
                    )
                arbiter.onDown(down.position.x, down.position.y)
                velocityTracker.resetTracking()
                velocityTracker.addPosition(down.uptimeMillis, down.position)
                motion.snapIfIdle()

                var tracking = !zoomedOut.value && !motion.animating.value

                // The direction this drag has already asked the warmer to
                // prepare for, so the preloader never flip-flops between
                // the two neighbors while the finger wavers.
                var warmingDirection = 0
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.size > 1) {
                        // A second finger is pinch intent: the zoomable
                        // takes the rest of this gesture.
                        if (tracking) motion.animateRestore()
                        tracking = false
                        gate.clear()
                        continue
                    }
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        if (tracking) {
                            val velocity = velocityTracker.calculateVelocity()
                            val release =
                                arbiter.onUp(
                                    velocityX = velocity.x,
                                    velocityY = velocity.y,
                                    hasPrevious = hasPrevious(),
                                    hasNext = hasNext(),
                                )
                            when (release) {
                                ViewerRelease.DismissViewer ->
                                    motion.animateDismiss { onDismiss() }

                                ViewerRelease.ShowDetails -> {
                                    onOpenInfo()
                                    motion.animateRestore()
                                    gate.clear()
                                }

                                is ViewerRelease.Navigate -> {
                                    val target = navigateTarget(release.delta)
                                    if (target == null) {
                                        // The arbiter already refuses edge-pointing
                                        // releases; this is the same answer for a
                                        // frame that vanished underneath.
                                        motion.animateRestore()
                                    } else {
                                        // Arm first — a fast fling never crosses
                                        // the warm threshold mid-drag — then hold
                                        // the image at its dragged offset until
                                        // the neighbor can paint. The
                                        // exit->swap->enter that follows starts
                                        // on a ready image, never on a blank
                                        // (v1.0.24).
                                        gate.arm(target)
                                        motion.animateNavigate(
                                            delta = release.delta,
                                            awaitReady = { gate.await(target, PRELOAD_TIMEOUT_MS) },
                                            onSwap = { onNavigate(release.delta) },
                                        )
                                    }
                                }

                                ViewerRelease.RestorePosition -> {
                                    motion.animateRestore()
                                    gate.clear()
                                }
                            }
                        }
                        break
                    }
                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                    if (!tracking) continue
                    if (change.isConsumed) {
                        // The zoomable claimed this drag mid-flight (a
                        // zoom began) — settle home once and stay out of
                        // it. Guarded so a drag the zoomable owns start
                        // to finish fires exactly one restore (v1.0.25):
                        // the unguarded version stacked a spring per
                        // pointer move and the interleaved writes read as
                        // the image shivering along its drag axis.
                        if (tracking) {
                            tracking = false
                            motion.animateRestore()
                            gate.clear()
                        }
                        continue
                    }
                    if (arbiter.onMove(change.position.x, change.position.y) != ViewerGesture.NONE) {
                        // A sideways drag most of the way to a commit points
                        // at a real neighbor: warm it now, while the finger is
                        // still travelling, so the transition that follows
                        // the release gets a head start on the load.
                        if (arbiter.gesture == ViewerGesture.HORIZONTAL) {
                            val direction = if (arbiter.totalX < 0f) +1 else -1
                            if (direction != warmingDirection &&
                                abs(arbiter.totalX) >= navigateCommitPx * PRELOAD_ARM_FRACTION
                            ) {
                                navigateTarget(direction)?.let(gate::arm)
                                warmingDirection = direction
                            }
                        }
                        motion.offsetX.floatValue =
                            arbiter.followX(hasNext = hasNext(), hasPrevious = hasPrevious())
                        motion.offsetY.floatValue = arbiter.followY(detailsNudgeCapPx)
                        change.consume()
                    }
                }
            }
        },
    )

/**
 * The offscreen half of the preload gate (v1.0.24): an invisible copy of
 * the viewer's own image stack for the wallpaper a swipe points at. It
 * shares the real request's shape — same URL, same retry-0 cache key,
 * same layout size — so whatever loads here is exactly what the incoming
 * preview paints, and the moment it draws, the gate knows the page
 * transition can run without a blank beat. A neighbor that fails instead
 * unlocks the page immediately — the swap lands on the retry overlay,
 * the same answer a slow load gets.
 */
@Composable
private fun PreloadedViewerImage(
    wallpaper: Wallpaper,
    gate: ViewerPreloadGate,
) {
    val context = LocalContext.current
    val zoomableState = rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = MAX_ZOOM))
    val imageState = rememberZoomableImageState(zoomableState)

    LaunchedEffect(imageState) {
        snapshotFlow { imageState.isImageDisplayed }.first { it }
        gate.report(wallpaper, displayed = true)
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                // Almost fully transparent: enough to be laid out and drawn
                // (so telephoto actually loads it), far too little to be
                // seen over the wallpaper on screen.
                .graphicsLayer { alpha = PRELOAD_ALPHA },
    ) {
        ZoomableAsyncImage(
            model =
                ImageRequest
                    .Builder(context)
                    .data(wallpaper.fullUrl)
                    // The retry-0 key matches the fresh preview this warms,
                    // so its result is the one reused on swap.
                    .setParameter("retry", 0, memoryCacheKey = "retry-0")
                    .listener(
                        onError = { _, _ -> gate.report(wallpaper, displayed = false) },
                    ).build(),
            contentDescription = null,
            state = imageState,
            modifier = Modifier.fillMaxSize(),
        )
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

/** Zoom ceiling for the preview, relative to the image's native size. */
private const val MAX_ZOOM = 5f

/** Movement (either axis) after which the drag locks to its dominant axis. */
private val LOCK_SLOP = 10.dp

/** A downward fling this fast dismisses even without the distance. */
private val DISMISS_FLING_VELOCITY = 2000.dp

/** Upward travel that opens the details panel on release — the pill's own. */
private val DETAILS_COMMIT_DISTANCE = 64.dp
private val DETAILS_FLING_VELOCITY = 800.dp

/** How far the details nudge may lift the image while the finger drags up. */
private val DETAILS_NUDGE_CAP = 24.dp

/** Screen-width fraction a sideways drag must cross to commit a page. */
private const val NAVIGATE_COMMIT_FRACTION = 0.25f

/** A sideways fling this fast pages even without the distance. */
private val NAVIGATE_FLING_VELOCITY = 1400.dp

/** How far into a commit a sideways drag must be before its neighbor warms up. */
private const val PRELOAD_ARM_FRACTION = 0.4f

/** How long a release holds the image waiting for an unwarmed neighbor. */
private const val PRELOAD_TIMEOUT_MS = 600L

/** The offscreen warmer's opacity: drawn, but never seen. */
private const val PRELOAD_ALPHA = 0.02f

/** Grace before the blurred backdrop and progress chip show themselves. */
private const val LOADING_FURNITURE_GRACE_MS = 150L
