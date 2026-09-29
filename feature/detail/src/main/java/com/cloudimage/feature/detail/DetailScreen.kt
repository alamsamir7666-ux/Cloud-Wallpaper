package com.cloudimage.feature.detail

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.cloudimage.core.data.repository.ApplyTarget
import com.cloudimage.core.model.Wallpaper
import com.cloudimage.core.model.WallpaperDetails
import java.io.File
import java.util.Locale

/**
 * Fullscreen preview + apply screen (v1.0.17; gestures reworked v1.0.23):
 * a gallery-grade zoomable image that stays crisp at any zoom, with one
 * gesture per swipe direction — down dismisses (1:1 translate plus fade,
 * never a scale), left/right page through the list the viewer was opened
 * from (gallery feed, library tab, or "More like this"), up opens the
 * details panel like the bottom pill it echoes. The chrome (top bar,
 * carousel, action row) rides the same dismiss fade as the scrim. The
 * same-provider "More like this" carousel steps aside while zoomed so
 * nothing competes with pixel inspection. Favorite toggle, set-as-wallpaper
 * (home / lock / both), save-to-gallery and share ride as before; one-shot
 * results (snackbars, share intents) arrive through the event flow.
 */
@Composable
fun DetailScreen(
    onBack: () -> Unit,
    onOpenWallpaper: (wallpapers: List<Wallpaper>, index: Int) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showTargetSheet by remember { mutableStateOf(false) }
    var showInfoSheet by remember { mutableStateOf(false) }
    val wallpaper = state.wallpaper

    // The viewer's live motion (v1.0.23): hoisted above the per-wallpaper
    // key below so a committed swipe can animate its exit, swap the
    // wallpaper, and slide the next one in — the offsets outlive the swap
    // while the zoom states reset with the image. zoomedOut and
    // imageDisplayed report the preview's state upward the same way.
    val motionScope = rememberCoroutineScope()
    val motion = remember(motionScope) { ViewerMotionState(motionScope) }
    val zoomedOut = remember { mutableStateOf(false) }
    val imageDisplayed = remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                DetailEvent.WallpaperApplied ->
                    snackbarHostState.showSnackbar(context.getString(R.string.detail_applied))
                DetailEvent.WallpaperSaved ->
                    snackbarHostState.showSnackbar(context.getString(R.string.detail_saved))
                is DetailEvent.ShareReady -> shareWallpaper(context, event.filePath, event.mimeType)
                is DetailEvent.ActionFailed -> {
                    val reason = context.getString(event.error.reason())
                    val action = context.getString(event.action.label())
                    snackbarHostState.showSnackbar(context.getString(R.string.detail_error_snackbar, action, reason))
                }
            }
        }
    }

    Box(
        // The viewer draws — and fades — its own scrim; solid black stays
        // only behind the invalid-destination dead end so its light text
        // keeps a home.
        modifier =
            modifier
                .fillMaxSize()
                .background(if (wallpaper == null) Color.Black else Color.Transparent),
    ) {
        if (wallpaper == null) {
            InvalidDestination(onBack = onBack)
        } else {
            key("${wallpaper.providerId}:${wallpaper.id}") {
                ZoomableWallpaperPreview(
                    wallpaper = wallpaper,
                    zoomedOut = zoomedOut,
                    imageDisplayed = imageDisplayed,
                    motion = motion,
                    onDismiss = onBack,
                    onNavigate = viewModel::onNavigate,
                    onOpenInfo = { showInfoSheet = true },
                    hasNext = state.hasNext,
                    hasPrevious = state.hasPrevious,
                    navigateTarget = viewModel::peekNeighbor,
                )
            }
            DetailTopBar(
                isFavorite = state.isFavorite,
                onBack = onBack,
                onToggleFavorite = viewModel::onToggleFavorite,
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .graphicsLayer { alpha = 1f - motion.dismissProgress },
            )
            DetailBottomActions(
                zoomed = zoomedOut.value,
                applyBusy = state.applyOp is OperationState.Running,
                saveBusy = state.saveOp is OperationState.Running,
                shareBusy = state.shareOp is OperationState.Running,
                saveSucceeded = state.saveOp is OperationState.Succeeded,
                downloadProgress = state.downloadProgress,
                isDownloaded = state.isDownloaded,
                moreLikeThis = state.moreLikeThis,
                onOpenWallpaper = { wallpapers, index ->
                    // Browsing onward from a lookalike tap: prepend the
                    // wallpaper on screen so "previous" from the first
                    // lookalike steps back to it.
                    onOpenWallpaper(listOf(wallpaper) + wallpapers, index + 1)
                },
                onOpenInfo = { showInfoSheet = true },
                onSetWallpaper = { showTargetSheet = true },
                onSave = viewModel::onSave,
                onShare = viewModel::onShare,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .graphicsLayer { alpha = 1f - motion.dismissProgress },
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .graphicsLayer { alpha = 1f - motion.dismissProgress }
                    .navigationBarsPadding()
                    .padding(bottom = 128.dp),
        )
    }

    if (showTargetSheet && wallpaper != null) {
        TargetSheet(
            onPick = { target ->
                showTargetSheet = false
                viewModel.onApply(target)
            },
            onDismiss = { showTargetSheet = false },
        )
    }
    if (showInfoSheet && wallpaper != null) {
        InfoSheet(
            wallpaper = wallpaper,
            details = state.details,
            onDismiss = { showInfoSheet = false },
        )
    }
}

@Composable
private fun DetailTopBar(
    isFavorite: Boolean,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        ScrimIconButton(
            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = stringResource(R.string.detail_back),
            onClick = onBack,
        )
        ScrimIconButton(
            imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            contentDescription =
                stringResource(
                    if (isFavorite) R.string.detail_remove_favorite else R.string.detail_add_favorite,
                ),
            tint = if (isFavorite) HeartRed else Color.White,
            onClick = onToggleFavorite,
        )
    }
}

@Composable
private fun ScrimIconButton(
    imageVector: ImageVector,
    contentDescription: String,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.background(Color.Black.copy(alpha = 0.35f), CircleShape).padding(4.dp).size(24.dp),
        )
    }
}

/**
 * The viewer's bottom furniture, stacked bottom-up: the action bar, the
 * swipe-up affordance for the info sheet, and the same-provider carousel —
 * which steps aside while the image is zoomed so nothing competes with
 * pixel inspection (v1.0.17).
 */
@Composable
private fun DetailBottomActions(
    zoomed: Boolean,
    applyBusy: Boolean,
    saveBusy: Boolean,
    shareBusy: Boolean,
    saveSucceeded: Boolean,
    downloadProgress: DownloadProgress?,
    isDownloaded: Boolean,
    moreLikeThis: List<Wallpaper>,
    onOpenWallpaper: (wallpapers: List<Wallpaper>, index: Int) -> Unit,
    onOpenInfo: () -> Unit,
    onSetWallpaper: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().navigationBarsPadding()) {
        AnimatedVisibility(
            visible = moreLikeThis.isNotEmpty() && !zoomed,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            MoreLikeThisRow(
                wallpapers = moreLikeThis,
                onOpenWallpaper = onOpenWallpaper,
            )
        }
        InfoHandleRow(onOpenInfo = onOpenInfo)
        DetailBottomBar(
            applyBusy = applyBusy,
            saveBusy = saveBusy,
            shareBusy = shareBusy,
            saveSucceeded = saveSucceeded,
            downloadProgress = downloadProgress,
            isDownloaded = isDownloaded,
            onSetWallpaper = onSetWallpaper,
            onSave = onSave,
            onShare = onShare,
        )
    }
}

/**
 * "Swipe up for details" (v1.0.17): the pill follows an upward drag at
 * half speed and springs back on release; crossing the threshold or
 * flinging up opens the info sheet with a light haptic tick. A plain tap
 * works too — the gesture is a bonus, never the only way in.
 */
@Composable
private fun InfoHandleRow(
    onOpenInfo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    var dragPx by remember { mutableFloatStateOf(0f) }
    val nudgePx by animateFloatAsState(
        targetValue = dragPx,
        animationSpec =
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
        label = "info-handle-nudge",
    )
    val openThresholdPx = with(density) { INFO_OPEN_THRESHOLD.toPx() }
    val maxDragPx = with(density) { INFO_HANDLE_MAX_DRAG.toPx() }
    val flingVelocityPx = with(density) { INFO_FLING_VELOCITY.toPx() }

    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .fillMaxWidth()
                .height(INFO_HANDLE_HEIGHT)
                .draggable(
                    state =
                        rememberDraggableState { delta ->
                            // Upward movement (negative delta) accumulates distance.
                            dragPx = (dragPx - delta).coerceIn(0f, maxDragPx)
                        },
                    orientation = Orientation.Vertical,
                    onDragStarted = { },
                    onDragStopped = { velocity ->
                        if (dragPx >= openThresholdPx || velocity <= -flingVelocityPx) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onOpenInfo()
                        }
                        dragPx = 0f
                    },
                ).clickable(onClick = onOpenInfo),
    ) {
        Surface(
            color = Color.White.copy(alpha = 0.12f),
            shape = RoundedCornerShape(50),
            modifier =
                Modifier.graphicsLayer {
                    translationY = -nudgePx / INFO_HANDLE_FOLLOW_FACTOR
                },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowUp,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource(R.string.detail_info_hint),
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

/**
 * The viewer's action row (v1.0.22): "Set wallpaper" on the left, download
 * and share as circular icon buttons on the right with a deliberate gap —
 * twice the row's rhythm — so the two read as distinct actions, not one
 * clustered control.
 */
@Composable
private fun DetailBottomBar(
    applyBusy: Boolean,
    saveBusy: Boolean,
    shareBusy: Boolean,
    saveSucceeded: Boolean,
    downloadProgress: DownloadProgress?,
    isDownloaded: Boolean,
    onSetWallpaper: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Button(onClick = onSetWallpaper, enabled = !applyBusy) {
            if (applyBusy) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Icon(Icons.Rounded.Wallpaper, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.detail_set_wallpaper))
        }
        Spacer(Modifier.weight(1f))
        DownloadButton(
            busy = saveBusy,
            succeeded = saveSucceeded,
            downloaded = isDownloaded,
            progress = downloadProgress,
            onClick = onSave,
        )
        Spacer(Modifier.width(12.dp))
        CircleActionButton(
            icon = Icons.Rounded.Share,
            contentDescription = stringResource(R.string.detail_share),
            enabled = !shareBusy,
            onClick = onShare,
        )
    }
}

/**
 * A 40 dp circular action button on the FilledTonal palette — the share
 * action's own shape, and the idle shape of the download button.
 */
@Composable
private fun CircleActionButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.size(40.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * The download action, in its four faces (v1.0.22):
 *
 * - **idle** — the download icon, tappable;
 * - **running** — the icon is replaced by a progress ring tracing the
 *   button's edge clockwise as the bytes land, with a small
 *   "1.2 MB / 4.5 MB" label directly below the button (the label, not the
 *   button, carries the numbers — the button is small and circular). An
 *   unknown Content-Length degrades the ring to indeterminate and the label
 *   to the running count alone;
 * - **complete** — a static checkmark, not tappable: once downloaded the
 *   button stays a checkmark (the user's chosen end state) and re-saving
 *   is simply not offered;
 * - **failed** — back to the idle face; the retry is a fresh tap.
 *
 * The button's right edge never moves: the column is end-aligned, so the
 * size label grows leftward into the space the row's weight spacer already
 * reserves.
 */
@Composable
private fun DownloadButton(
    busy: Boolean,
    succeeded: Boolean,
    downloaded: Boolean,
    progress: DownloadProgress?,
    onClick: () -> Unit,
) {
    val complete = succeeded || downloaded
    val running = busy && !complete
    Column(horizontalAlignment = Alignment.End, modifier = Modifier.testTag("detail:download")) {
        Box(contentAlignment = Alignment.Center) {
            Surface(
                onClick = onClick,
                enabled = !busy && !complete,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    when {
                        complete ->
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = stringResource(R.string.detail_downloaded),
                                modifier = Modifier.size(18.dp),
                            )
                        // The icon gives way to the ring while the bytes land.
                        running -> Unit
                        else ->
                            Icon(
                                Icons.Rounded.Download,
                                contentDescription = stringResource(R.string.detail_save),
                                modifier = Modifier.size(18.dp),
                            )
                    }
                }
            }
            if (running) {
                val fraction = progress?.fraction
                if (fraction != null) {
                    CircularProgressIndicator(
                        progress = { fraction },
                        strokeWidth = 3.dp,
                        strokeCap = StrokeCap.Round,
                        trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.35f),
                        modifier = Modifier.matchParentSize(),
                    )
                } else {
                    CircularProgressIndicator(
                        strokeWidth = 3.dp,
                        trackColor = Color.Transparent,
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = running && progress != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            val label =
                when (val current = progress) {
                    null -> ""
                    else ->
                        if (current.totalBytes != null && current.totalBytes > 0) {
                            "${formatFileSize(current.bytesRead)} / ${formatFileSize(current.totalBytes)}"
                        } else {
                            formatFileSize(current.bytesRead)
                        }
                }
            Surface(
                color = Color.White.copy(alpha = 0.12f),
                shape = RoundedCornerShape(50),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
        }
    }
}

/**
 * The "More like this" carousel (v1.0.9): compact same-provider lookalikes
 * mirroring the home carousels' card metrics at a smaller scale so the
 * fullscreen image stays the hero. It only exists when the ViewModel
 * found something — empty means hidden — and it slides away while zoomed.
 * Since v1.0.23 a tap hands the whole row (plus the wallpaper it belongs
 * to) to navigation, so the opened viewer can page through the row with
 * sideways swipes.
 */
@Composable
private fun MoreLikeThisRow(
    wallpapers: List<Wallpaper>,
    onOpenWallpaper: (wallpapers: List<Wallpaper>, index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .testTag("detail:more-like-this"),
    ) {
        Text(
            text = stringResource(R.string.detail_more_like_this),
            style = MaterialTheme.typography.titleSmall,
            color = Color.White.copy(alpha = 0.9f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.heightIn(min = LOOKALIKE_HEIGHT + 4.dp),
        ) {
            itemsIndexed(
                wallpapers,
                key = { _, wallpaper -> "${wallpaper.providerId}:${wallpaper.id}" },
            ) { index, wallpaper ->
                LookalikeCard(
                    wallpaper = wallpaper,
                    onClick = { onOpenWallpaper(wallpapers, index) },
                )
            }
        }
    }
}

/** One compact carousel card — a thumbnail sized by its real aspect ratio. */
@Composable
private fun LookalikeCard(
    wallpaper: Wallpaper,
    onClick: () -> Unit,
) {
    val ratio =
        wallpaper.aspectRatio?.coerceIn(minimumValue = 0.5f, maximumValue = 2.4f) ?: 1.4f
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        modifier =
            Modifier
                .height(LOOKALIKE_HEIGHT)
                .aspectRatio(ratio)
                .testTag("detail:more-like-this:${wallpaper.providerId}:${wallpaper.id}"),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model =
                    ImageRequest
                        .Builder(LocalContext.current)
                        .data(wallpaper.thumbUrl)
                        .crossfade(durationMillis = 220)
                        .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                placeholder = ColorPainter(Color.White.copy(alpha = 0.08f)),
                error = ColorPainter(Color.White.copy(alpha = 0.08f)),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetSheet(
    onPick: (ApplyTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Text(
            text = stringResource(R.string.detail_apply_target_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.detail_target_home)) },
            supportingContent = { Text(stringResource(R.string.detail_target_home_hint)) },
            leadingContent = { Icon(Icons.Rounded.Home, contentDescription = null) },
            modifier = Modifier.clickable { onPick(ApplyTarget.HOME) },
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.detail_target_lock)) },
            supportingContent = { Text(stringResource(R.string.detail_target_lock_hint)) },
            leadingContent = { Icon(Icons.Rounded.Lock, contentDescription = null) },
            modifier = Modifier.clickable { onPick(ApplyTarget.LOCK) },
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.detail_target_both)) },
            supportingContent = { Text(stringResource(R.string.detail_target_both_hint)) },
            leadingContent = { Icon(Icons.Rounded.Apps, contentDescription = null) },
            modifier = Modifier.clickable { onPick(ApplyTarget.BOTH) },
        )
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun InfoSheet(
    wallpaper: Wallpaper,
    details: WallpaperDetails?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Text(
            text = stringResource(R.string.detail_info_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        InfoRow(
            label = stringResource(R.string.detail_info_resolution),
            // The listing's dimensions when it published true ones; the
            // source's definitive record fills what the listing could not
            // state (HDQWalls's grid carries only its card crop) — never a
            // wrong number over a missing one.
            value = (wallpaper.resolution() ?: details?.resolution).orDash(),
        )
        InfoRow(
            label = stringResource(R.string.detail_info_file_size),
            value = details?.fileSizeBytes?.let(::formatFileSize).orDash(),
        )
        InfoRow(label = stringResource(R.string.detail_info_provider), value = wallpaper.providerId)
        InfoRow(
            label = stringResource(R.string.detail_info_rating),
            value = wallpaper.contentRating.name.lowercase(),
        )
        InfoRow(label = stringResource(R.string.detail_info_id), value = wallpaper.id)
        if (wallpaper.tags.isNotEmpty()) {
            Text(
                text = stringResource(R.string.detail_info_tags),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 24.dp),
            ) {
                wallpaper.tags.take(MAX_INFO_TAG_CHIPS).forEach { tag ->
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            text = tag,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier =
                                Modifier
                                    .widthIn(max = 220.dp)
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
        if (wallpaper.sourceUrl != null) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.detail_info_source)) },
                supportingContent = { Text(wallpaper.sourceUrl.orEmpty()) },
                leadingContent = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null) },
                modifier =
                    Modifier.clickable {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(wallpaper.sourceUrl)))
                        }
                    },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { Text(value) },
    )
}

@Composable
private fun InvalidDestination(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
    ) {
        Spacer(Modifier.height(48.dp))
        Icon(
            imageVector = Icons.Rounded.Wallpaper,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(56.dp),
        )
        Text(
            text = stringResource(R.string.detail_invalid_title),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
        Text(
            text = stringResource(R.string.detail_invalid_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = Color.White.copy(alpha = 0.7f),
        )
        Button(onClick = onBack) { Text(stringResource(R.string.detail_back_to_browse)) }
    }
}

/** Resource id for the user-facing reason behind each error. */
private fun DetailError.reason(): Int =
    when (this) {
        DetailError.OFFLINE -> R.string.detail_error_offline
        DetailError.TIMEOUT -> R.string.detail_error_timeout
        DetailError.HTTP -> R.string.detail_error_http
        DetailError.BAD_IMAGE -> R.string.detail_error_bad_image
        DetailError.UNSUPPORTED -> R.string.detail_error_unsupported
        DetailError.STORAGE -> R.string.detail_error_storage
    }

/** Resource id naming the operation that failed. */
private fun DetailAction.label(): Int =
    when (this) {
        DetailAction.APPLY -> R.string.detail_error_action_apply
        DetailAction.SAVE -> R.string.detail_error_action_save
        DetailAction.SHARE -> R.string.detail_error_action_share
    }

private fun Wallpaper.resolution(): String? = if (width != null && height != null) "${width}x$height" else null

/**
 * File size the way the sites label their own downloads: KB/MB/GB with
 * two decimals, never a bare byte count (3,627,606 B is "3.46 MB" — the
 * same figure hdqwalls prints next to its Download Original button).
 */
private fun formatFileSize(bytes: Long): String {
    val gigabytes = bytes / (1024.0 * 1024 * 1024)
    val megabytes = bytes / (1024.0 * 1024)
    val kilobytes = bytes / 1024.0
    return when {
        gigabytes >= 1 -> String.format(Locale.US, "%.2f GB", gigabytes)
        megabytes >= 1 -> String.format(Locale.US, "%.2f MB", megabytes)
        else -> String.format(Locale.US, "%.0f KB", kilobytes)
    }
}

private fun String?.orDash(): String = this ?: "—"

private val HeartRed = Color(0xFFEF6C74)

/** Height of one "More like this" card. */
private val LOOKALIKE_HEIGHT = 128.dp

/** The swipe-up handle: row height, drag gating, and feedback tuning. */
private val INFO_HANDLE_HEIGHT = 48.dp
private val INFO_OPEN_THRESHOLD = 48.dp
private val INFO_HANDLE_MAX_DRAG = 120.dp
private val INFO_FLING_VELOCITY = 800.dp
private const val INFO_HANDLE_FOLLOW_FACTOR = 2f

/** Tags shown in the info sheet before the list is cut. */
private const val MAX_INFO_TAG_CHIPS = 12

/** Fires the system share sheet with a FileProvider-backed image uri. */
private fun shareWallpaper(
    context: Context,
    filePath: String,
    mimeType: String,
) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(filePath))
    val intent =
        Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.detail_share_title)))
}
