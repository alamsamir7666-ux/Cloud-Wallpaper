package com.cloudimage.feature.extensions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cloudimage.extensions.core.RepoError
import com.cloudimage.extensions.core.StoredRepo
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The extensions screen's entry point (v1.0.20, Cloudstream structure): a
 * repository browser. Each row is one added repository — tapping it opens
 * its catalog; the trailing icon removes it. The fixed bottom bar counts
 * the install base in three populations (Downloaded / Disabled / Not
 * downloaded) and opens the installed list.
 */
@Composable
fun ExtensionsScreen(
    onOpenRepo: (StoredRepo) -> Unit,
    onOpenInstalled: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ExtensionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var pendingRemoveRepo by remember { mutableStateOf<StoredRepo?>(null) }
    var showAddRepo by remember { mutableStateOf(false) }

    val addedMessage = stringResource(R.string.extensions_repo_added)
    val copiedMessage = stringResource(R.string.extensions_repo_copied)
    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is ExtensionsEvent.RepoAdded -> {
                    showAddRepo = false
                    snackbarHostState.showSnackbar(addedMessage)
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (!state.loading) {
                ExtendedFloatingActionButton(
                    onClick = { showAddRepo = true },
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.extensions_add_repo)) },
                )
            }
        },
        bottomBar = {
            if (!state.loading) {
                CountsBar(
                    state = state,
                    onOpenInstalled = onOpenInstalled,
                )
            }
        },
        modifier = modifier.fillMaxSize(),
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(padding),
        ) {
            if (state.loading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                RepoBrowserList(
                    state = state,
                    onOpenRepo = onOpenRepo,
                    onRemoveRepo = { pendingRemoveRepo = it },
                    onCopyRepo = { repo ->
                        copyRepoDetails(clipboard, repo)
                        scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                    },
                )
            }
        }
    }

    pendingRemoveRepo?.let { repo ->
        RemoveRepoDialog(
            repo = repo,
            onConfirm = {
                viewModel.removeRepo(repo)
                pendingRemoveRepo = null
            },
            onDismiss = { pendingRemoveRepo = null },
        )
    }

    if (showAddRepo) {
        // v1.0.13: the dialog lives until the add resolves — closing on
        // confirm hid the failure (nothing appeared to happen). Success
        // closes it via the RepoAdded event; a failure keeps it open so
        // the error shows where the user is looking.
        AddRepoDialog(
            adding = state.addingRepo,
            error = state.repoError,
            onConfirm = viewModel::addRepo,
            onDismiss = {
                viewModel.clearRepoError()
                showAddRepo = false
            },
        )
    }
}

/** Copies the Cloudstream shareable form — "name : url" — to the clipboard. */
private fun copyRepoDetails(
    clipboard: ClipboardManager,
    repo: StoredRepo,
) {
    clipboard.setText(AnnotatedString("${repo.name} : ${repo.url}"))
}

/**
 * The browser list: one full-bleed row per repository, Cloudstream
 * `repository_item` style — avatar, name, url, trailing delete.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RepoBrowserList(
    state: ExtensionsUiState,
    onOpenRepo: (StoredRepo) -> Unit,
    onRemoveRepo: (StoredRepo) -> Unit,
    onCopyRepo: (StoredRepo) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 24.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Text(
                text = stringResource(R.string.extensions_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 4.dp),
            )
        }
        if (state.repos.isEmpty()) {
            item { ReposEmptyState() }
        } else {
            items(
                state.repos,
                key = { it.id },
            ) { repo ->
                RepoRow(
                    repo = repo,
                    onClick = { onOpenRepo(repo) },
                    onLongClick = { onCopyRepo(repo) },
                    onRemove = { onRemoveRepo(repo) },
                )
            }
        }
    }
}

/** One repository row — tap to browse, long-press to copy, icon to remove. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RepoRow(
    repo: StoredRepo,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ).padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonogramAvatar(
            label = repo.name,
            seed = repo.id,
        )
        Spacer(Modifier.width(16.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = repo.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = repo.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier.testTag("extensions:repo-delete:${repo.id}"),
        ) {
            Icon(
                imageVector = Icons.Rounded.DeleteOutline,
                contentDescription = stringResource(R.string.extensions_remove_repo),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * The fixed bottom counts bar (Cloudstream's `plugin_storage_appbar`): a
 * segmented proportional bar for Downloaded / Disabled / Not downloaded,
 * a three-part legend, and a tap that opens the installed list. Segments
 * with zero members are absent, never zero-weighted — `weight()` throws
 * on 0 (the v1.0.10 crash); an empty install base renders a neutral track.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CountsBar(
    state: ExtensionsUiState,
    onOpenInstalled: () -> Unit,
) {
    val downloaded = state.downloadedCount
    val disabled = state.disabledCount
    val notDownloaded = state.notDownloadedCount
    val segments = state.statsSegments()
    val downloadedColor = MaterialTheme.colorScheme.primary
    val disabledColor = MaterialTheme.colorScheme.tertiary
    val notDownloadedColor = MaterialTheme.colorScheme.secondaryContainer
    Surface(
        tonalElevation = 3.dp,
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("extensions:stats"),
    ) {
        Column(
            modifier =
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(onClick = onOpenInstalled)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.extensions_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .clip(RoundedCornerShape(6.dp)),
            ) {
                if (segments.isEmpty()) {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                } else {
                    segments.forEach { segment ->
                        val color =
                            when (segment.kind) {
                                ExtensionStatKind.DOWNLOADED -> downloadedColor
                                ExtensionStatKind.DISABLED -> disabledColor
                                ExtensionStatKind.NOT_DOWNLOADED -> notDownloadedColor
                            }
                        Box(
                            Modifier
                                .weight(segment.count.toFloat())
                                .fillMaxSize()
                                .background(color),
                        )
                    }
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                StatLegend(color = downloadedColor, label = stringResource(R.string.extensions_stats_downloaded, downloaded))
                StatLegend(color = disabledColor, label = stringResource(R.string.extensions_stats_disabled, disabled))
                StatLegend(
                    color = notDownloadedColor,
                    label = stringResource(R.string.extensions_stats_not_downloaded, notDownloaded),
                )
            }
        }
    }
}

@Composable
private fun StatLegend(
    color: Color,
    label: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier =
                Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
private fun ReposEmptyState() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 48.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Extension,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
        Text(
            text = stringResource(R.string.extensions_repos_empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.extensions_repos_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AddRepoDialog(
    adding: Boolean,
    error: RepoError?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.extensions_add_repo_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = stringResource(R.string.extensions_add_repo_body))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.extensions_add_repo_label)) },
                    singleLine = true,
                    isError = error != null,
                    trailingIcon = {
                        if (url.isNotEmpty()) {
                            IconButton(
                                onClick = { url = "" },
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.extensions_search_clear),
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Text(
                        text = stringResource(R.string.extensions_add_repo_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(url) },
                enabled = url.isNotBlank() && !adding,
            ) {
                Text(text = stringResource(R.string.extensions_add_repo_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.extensions_add_repo_cancel))
            }
        },
    )
}

@Composable
private fun RemoveRepoDialog(
    repo: StoredRepo,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.extensions_remove_repo_title)) },
        text = { Text(text = stringResource(R.string.extensions_remove_repo_body, repo.name)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.extensions_remove_repo_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.extensions_remove_repo_cancel))
            }
        },
    )
}
