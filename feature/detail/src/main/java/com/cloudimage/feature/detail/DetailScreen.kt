package com.cloudimage.feature.detail

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import java.io.File

/**
 * Fullscreen preview + apply screen: zoomable image, favorite toggle, an info
 * sheet, set-as-wallpaper (home / lock / both), save-to-gallery and share.
 * One-shot results (snackbars, share intents) arrive through the event flow.
 * When the source declared SEARCH + TAGS and the item carries tags, a
 * same-provider "More like this" carousel rides above the action bar
 * (v1.0.9) — tapping a card reopens detail for that wallpaper.
 */
@Composable
fun DetailScreen(
    onBack: () -> Unit,
    onOpenWallpaper: (Wallpaper) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showTargetSheet by remember { mutableStateOf(false) }
    var showInfoSheet by remember { mutableStateOf(false) }
    val wallpaper = state.wallpaper

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

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (wallpaper == null) {
            InvalidDestination(onBack = onBack)
        } else {
            ZoomableWallpaperPreview(wallpaper = wallpaper)
            DetailTopBar(
                isFavorite = state.isFavorite,
                onBack = onBack,
                onToggleFavorite = viewModel::onToggleFavorite,
                onOpenInfo = { showInfoSheet = true },
            )
            DetailBottomBar(
                applyBusy = state.applyOp is OperationState.Running,
                saveBusy = state.saveOp is OperationState.Running,
                shareBusy = state.shareOp is OperationState.Running,
                onSetWallpaper = { showTargetSheet = true },
                onSave = viewModel::onSave,
                onShare = viewModel::onShare,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            if (state.moreLikeThis.isNotEmpty()) {
                MoreLikeThisRow(
                    wallpapers = state.moreLikeThis,
                    onOpenWallpaper = onOpenWallpaper,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp),
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
            onDismiss = { showInfoSheet = false },
        )
    }
}

@Composable
private fun DetailTopBar(
    isFavorite: Boolean,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenInfo: () -> Unit,
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
        Row {
            ScrimIconButton(
                imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription =
                    stringResource(
                        if (isFavorite) R.string.detail_remove_favorite else R.string.detail_add_favorite,
                    ),
                tint = if (isFavorite) HeartRed else Color.White,
                onClick = onToggleFavorite,
            )
            ScrimIconButton(
                imageVector = Icons.Rounded.Info,
                contentDescription = stringResource(R.string.detail_info),
                onClick = onOpenInfo,
            )
        }
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

@Composable
private fun DetailBottomBar(
    applyBusy: Boolean,
    saveBusy: Boolean,
    shareBusy: Boolean,
    onSetWallpaper: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp),
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
        ActionIconButton(
            icon = Icons.Rounded.Download,
            contentDescription = stringResource(R.string.detail_save),
            busy = saveBusy,
            onClick = onSave,
        )
        ActionIconButton(
            icon = Icons.Rounded.Share,
            contentDescription = stringResource(R.string.detail_share),
            busy = shareBusy,
            onClick = onShare,
        )
    }
}

@Composable
private fun ActionIconButton(
    icon: ImageVector,
    contentDescription: String,
    busy: Boolean,
    onClick: () -> Unit,
) {
    FilledTonalButton(onClick = onClick, enabled = !busy, shape = CircleShape) {
        if (busy) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        } else {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * The "More like this" carousel (v1.0.9): compact same-provider lookalikes
 * riding above the action bar, mirroring the home carousels' card metrics
 * at a smaller scale so the fullscreen image stays the hero. It only
 * exists when the ViewModel found something — empty means hidden.
 */
@Composable
private fun MoreLikeThisRow(
    wallpapers: List<Wallpaper>,
    onOpenWallpaper: (Wallpaper) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 104.dp)
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
            items(wallpapers, key = { "${it.providerId}:${it.id}" }) { wallpaper ->
                LookalikeCard(
                    wallpaper = wallpaper,
                    onClick = { onOpenWallpaper(wallpaper) },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InfoSheet(
    wallpaper: Wallpaper,
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
            value = wallpaper.resolution().orDash(),
        )
        InfoRow(label = stringResource(R.string.detail_info_provider), value = wallpaper.providerId)
        InfoRow(
            label = stringResource(R.string.detail_info_rating),
            value = wallpaper.contentRating.name.lowercase(),
        )
        InfoRow(label = stringResource(R.string.detail_info_id), value = wallpaper.id)
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

private fun String?.orDash(): String = this ?: "—"

private val HeartRed = Color(0xFFEF6C74)

/** Height of one "More like this" card. */
private val LOOKALIKE_HEIGHT = 128.dp

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
