package com.cloudimage.feature.detail

import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
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
 * - a listing whose full URL is BLANK (v1.2.7: sources that mint the
 *   original's address per wallpaper) has nothing to load YET — the page
 *   keeps its blurred backdrop and loading furniture while the ViewModel
 *   resolves the real URL, starts the load the moment it lands, and offers
 *   the same retry surface if the resolution itself fails;
 * - a downward drag dismisses: the image translates down 1:1 with the
 *   finger and fades, and nothing scales, skews or resizes at any point;
 *   released past a threshold (or flung) the close continues on its own,
 *   otherwise everything springs back;
 * - an upward drag opens the details panel, mirroring the pill it echoes;
 * - sideways is not this page's business at all: the surrounding
 *   HorizontalPager owns left/right paging natively.
 *
 * The gesture choreography that makes the vertical drag coexist with
 * telephoto's zoomable: the gesture layer is the PARENT of everything that
 * moves — a wrapper Box whose pointer input sits above the image in the
 * tree (and therefore below it in the Main-pass dispatch order). The
 * detector consumes the vertical touch-slop event itself, which is the
 * exact handshake telephoto is built to honor: its forked transformable
 * watches for exactly that consumption while it waits for its own slop
 * and stands down the moment it sees it, and the pager above — dispatched
 * after descendants — finds the moves consumed and stops paging for the
 * rest of the gesture. One gesture, one writer, with the slop race, not
 * interleaved bookkeeping, deciding who owns the finger:
 *
 * - a pinch grabs the zoomable first (multi-pointer events cross its slop
 *   unconditionally, and children dispatch first), so the layer never
 *   even locks;
 * - a double-tap-and-hold drag (quick zoom) is claimed by the zoomable's
 *   own second-down slop detector — again child-first — before this
 *   layer's slop can fire;
 * - a zoomed-in pan has pan room, so the zoomable consumes every move and
 *   this layer's slop helper sees them consumed and stands down (and
 *   while zoomed at all, the layer declines to participate from the
 *   first down);
 * - a horizontal swipe never crosses vertical slop, so the pager's own
 *   horizontal slop wins the race and pages.
 *
 * This is the choreography telephoto's own media-viewer sample and its
 * FlickToDismiss() component use (v1.0.22 of this app did too) — adopted
 * here directly after the below-sibling variant of v1.0.29 starved on
 * real devices: a layer that only consumes after its own private lock
 * engages too late, after the zoomable's nodes have already claimed the
 * gesture in the child-first dispatch order.
 */
@Composable
internal fun WallpaperViewerPage(
    wallpaper: Wallpaper,
    motion: ViewerMotionState,
    isActivePage: Boolean,
    onDismiss: () -> Unit,
    onOpenInfo: () -> Unit,
    onZoomedChange: (Boolean) -> Unit,
    resolutionFailed: Boolean = false,
    onRetryResolution: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val url = wallpaper.fullUrl

    // Load state keys on the URL (v1.2.7): a page slot's wallpaper can
    // change underneath it — the pager reuses compositions per index, and
    // the resolution flow replaces a blank-URL item in place — so a
    // failure or retry attempt belonging to the old URL must never leak
    // into the new one's load.
    var retryAttempt by remember(url) { mutableIntStateOf(0) }
    var loadFailed by remember(url) { mutableStateOf(false) }
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

    // A page that stops being the settled one snaps its zoom back to rest:
    // swiping away from a zoomed page and later returning never lands on
    // a page whose zoom would freeze the pager mid-settle.
    if (!isActivePage) {
        LaunchedEffect(zoomableState) {
            zoomableState.resetZoom(animationSpec = SnapSpec())
        }
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
        // The gesture wrapper — the PARENT of everything the finger moves.
        // Stationary itself (never inside the moving Box, whose offset
        // would feed the drag's own writes back into the pointer
        // coordinates): see the choreography note above.
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalDismissDrag(
                        motion = motion,
                        zoomFraction = { zoomableState.zoomFraction ?: 0f },
                        onDismiss = { currentOnDismiss() },
                        onOpenInfo = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            currentOnOpenInfo()
                        },
                    ),
        ) {
            // Everything the finger moves travels together — the backdrop,
            // the image and its loading furniture — on whole pixels,
            // through the LAYOUT (a sub-pixel layer translation would
            // re-sample the sub-sampled tiles at a fresh fractional
            // position every frame, which reads as a faint shimmer along
            // the drag axis). The fade stays a draw-phase concern.
            // Translation and alpha are the only two effects the dismiss
            // ever applies — never a scale.
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

                // The image itself only mounts once there is something to
                // load (v1.2.7): a BLANK full URL is not a load attempt but
                // the signal that the real address is still being resolved —
                // the blurred backdrop and its furniture cover that wait,
                // and the resolved URL arriving here is what starts the
                // download. Firing a request at nothing would only flash
                // the retry furniture for a failure that is not one.
                if (url.isNotBlank()) {
                    ZoomableAsyncImage(
                        model =
                            ImageRequest
                                .Builder(context)
                                .data(url)
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

                if (loadFailed || (url.isBlank() && resolutionFailed)) {
                    RetryOverlay(
                        onRetry = {
                            if (url.isNotBlank()) {
                                loadFailed = false
                                retryAttempt += 1
                            } else {
                                onRetryResolution()
                            }
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
}

/**
 * The vertical half of the viewer's gestures as a modifier on the wrapper
 * that parents the zoomable image: a dominant downward drag dismisses
 * (1:1 translate plus fade, no scaling ever) and a dominant upward one
 * opens details. The lock is compose's own vertical touch slop, consumed
 * as it is crossed — the handshake telephoto's zoomable honors by
 * standing down, and the pager honors by finding the moves consumed.
 * Everything the zoomable genuinely wants (a pinch, a quick zoom, a
 * zoomed-in pan) it takes first as the child, and this layer never
 * engages at all; everything the pager wants (sideways) never crosses
 * vertical slop. Real taps, double-tap zoom, quick zoom and pinches are
 * untouched.
 */
private fun Modifier.verticalDismissDrag(
    motion: ViewerMotionState,
    zoomFraction: () -> Float,
    onDismiss: () -> Unit,
    onOpenInfo: () -> Unit,
): Modifier =
    pointerInput(motion) {
        val dismissFlingPx = DISMISS_FLING_VELOCITY.toPx()
        val detailsCommitPx = DETAILS_COMMIT_DISTANCE.toPx()
        val detailsFlingPx = DETAILS_FLING_VELOCITY.toPx()
        val detailsNudgeCapPx = DETAILS_NUDGE_CAP.toPx()
        val velocityTracker = VelocityTracker()

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)

            // While the image is zoomed (or the dismiss exit owns the
            // screen), this layer does not participate at all: telephoto's
            // pan owns the finger for the whole gesture.
            if (zoomFraction() > ZOOMED_FRACTION || motion.dismissing.value) {
                return@awaitEachGesture
            }

            // The lock: vertical touch slop, consumed as it is crossed.
            // A null return means the finger lifted without crossing it (a
            // tap, or nothing), or that the zoomable consumed the moves
            // first (a pinch taking shape, a zoomed-in pan) — either way,
            // this gesture is not ours.
            val slopChange =
                awaitVerticalTouchSlopOrCancellation(down.id) { change, _ ->
                    // The consumption IS the handshake: telephoto's
                    // transformable sees it on its Final pass while still
                    // waiting for its own slop and stands down, and the
                    // pager above sees the moves consumed and stops paging
                    // for the rest of the gesture.
                    change.consume()
                } ?: return@awaitEachGesture

            val arbiter =
                VerticalDragArbiter(
                    dismissCommitPx = motion.dismissDistancePx,
                    dismissFlingPx = dismissFlingPx,
                    detailsCommitPx = detailsCommitPx,
                    detailsFlingPx = detailsFlingPx,
                )
            arbiter.onDown(down.position.x, down.position.y)
            arbiter.onLocked(downward = slopChange.position.y > down.position.y)
            arbiter.onMove(slopChange.position.x, slopChange.position.y)

            velocityTracker.resetTracking()
            velocityTracker.addPosition(down.uptimeMillis, down.position)
            velocityTracker.addPosition(slopChange.uptimeMillis, slopChange.position)

            // The first locked write takes over from whatever the offset is
            // right now (a settle animation in flight stops dead here), so
            // the follow is continuous with the image on screen — no snap.
            motion.cancelAnimations()
            motion.offsetY.floatValue =
                arbiter.followY(
                    currentOffsetY = motion.offsetY.floatValue,
                    detailsNudgeCapPx = detailsNudgeCapPx,
                )

            // The lock's tail: every further move of this pointer is
            // consumed, so the zoomable and the pager both stay stood down
            // until the finger lifts. If someone consumes one first (a
            // pinch joining mid-drag), the drag is cancelled and everything
            // settles home — never a fight.
            val completedNormally =
                drag(pointerId = slopChange.id) { change ->
                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                    arbiter.onMove(change.position.x, change.position.y)
                    motion.offsetY.floatValue =
                        arbiter.followY(
                            currentOffsetY = motion.offsetY.floatValue,
                            detailsNudgeCapPx = detailsNudgeCapPx,
                        )
                    change.consume()
                }

            if (completedNormally) {
                val velocity = velocityTracker.calculateVelocity()
                val velocityY = if (velocity.y.isNaN()) 0f else velocity.y
                when (arbiter.onUp(velocityY)) {
                    VerticalRelease.DismissViewer -> motion.animateDismiss(onDismiss)

                    VerticalRelease.ShowDetails -> {
                        onOpenInfo()
                        motion.animateRestore()
                    }

                    VerticalRelease.RestorePosition -> motion.animateRestore()
                }
            } else {
                // Someone else claimed the gesture mid-flight — settle
                // home and stay out of it.
                motion.animateRestore()
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

/** Zoom ceiling for the preview, relative to the image's native size. */
private const val MAX_ZOOM = 5f

/** A downward fling this fast dismisses even without the distance. */
private val DISMISS_FLING_VELOCITY = 1250.dp

/** Upward travel that opens the details panel on release — the pill's own. */
private val DETAILS_COMMIT_DISTANCE = 64.dp
private val DETAILS_FLING_VELOCITY = 800.dp

/** How far the details nudge may lift the image while the finger drags up. */
private val DETAILS_NUDGE_CAP = 24.dp

/** Grace before the blurred backdrop and progress chip show themselves. */
private const val LOADING_FURNITURE_GRACE_MS = 150L
