package com.cloudimage.feature.extensions

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cloudimage.extensions.core.RepoPackageEntry
import kotlinx.coroutines.flow.collectLatest

/**
 * One repository's catalog (v1.0.20, Cloudstream's plugin list): search
 * over the entries, category filter chips when the index declares
 * categories, and one row per extension — icon, name, version, size,
 * short description, and a per-item download that becomes delete once
 * installed. Updates ride the download affordance as install-overs.
 */
@Composable
fun RepoDetailScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RepoDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingUninstall by remember { mutableStateOf<RepoPackageEntry?>(null) }

    val installedMessage = stringResource(R.string.extensions_installed_snackbar)
    val updatedMessage = stringResource(R.string.extensions_updated_snackbar)
    val installFailedMessage = stringResource(R.string.extensions_install_failed_snackbar)
    val uninstalledMessage = stringResource(R.string.extensions_uninstalled_snackbar)
    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is RepoDetailEvent.Installed -> snackbarHostState.showSnackbar(installedMessage.format(event.name))
                is RepoDetailEvent.Updated -> snackbarHostState.showSnackbar(updatedMessage.format(event.name))
                is RepoDetailEvent.InstallFailed ->
                    snackbarHostState.showSnackbar(installFailedMessage.format(event.reason))
                is RepoDetailEvent.Uninstalled -> snackbarHostState.showSnackbar(uninstalledMessage.format(event.name))
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier.fillMaxSize(),
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(padding),
        ) {
            RepoDetailToolbar(
                title = state.repo?.name.orEmpty(),
                loading = state.catalogStatus == RepoCatalogStatus.LOADING,
                onBack = onBack,
                onRefresh = viewModel::refresh,
            )
            if (state.catalogStatus == RepoCatalogStatus.READY) {
                CatalogSearchField(
                    query = state.query,
                    onQueryChange = viewModel::setQuery,
                )
                if (state.categories.isNotEmpty()) {
                    CategoryChipsRow(
                        categories = state.categories,
                        selected = state.category,
                        onSelect = viewModel::setCategory,
                    )
                }
            }
            when (state.catalogStatus) {
                RepoCatalogStatus.LOADING ->
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                RepoCatalogStatus.FAILED -> CatalogFailed(onRetry = viewModel::refresh)
                RepoCatalogStatus.READY -> {
                    val visible = state.visibleEntries()
                    LazyColumn(
                        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 24.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (visible.isEmpty()) {
                            item {
                                Text(
                                    text =
                                        stringResource(
                                            if (state.query.isNotBlank() || state.category != null) {
                                                R.string.extensions_catalog_no_matches
                                            } else {
                                                R.string.extensions_catalog_empty
                                            },
                                        ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(24.dp),
                                )
                            }
                        }
                        items(
                            visible,
                            key = { it.id },
                        ) { entry ->
                            CatalogEntryRow(
                                entry = entry,
                                installed = entry.id in state.installedManifests,
                                update = state.updateAvailable(entry),
                                installing = entry.id in state.installing,
                                failed = entry.id in state.failedInstalls,
                                onInstall = { viewModel.installPackage(entry) },
                                onUninstall = { pendingUninstall = entry },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingUninstall?.let { entry ->
        UninstallEntryDialog(
            entry = entry,
            onConfirm = {
                viewModel.uninstallPackage(entry)
                pendingUninstall = null
            },
            onDismiss = { pendingUninstall = null },
        )
    }
}

@Composable
private fun RepoDetailToolbar(
    title: String,
    loading: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.extensions_back),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (loading) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier =
                    Modifier
                        .padding(end = 12.dp)
                        .size(24.dp),
            )
        }
        IconButton(onClick = onRefresh) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = stringResource(R.string.extensions_refresh_repo),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CatalogSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(stringResource(R.string.extensions_search_catalog)) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.testTag("extensions:query-clear"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.extensions_search_clear),
                    )
                }
            }
        },
        singleLine = true,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 4.dp)
                .testTag("extensions:query"),
    )
}

/**
 * The category filter — Cloudstream's tv-type chips: "All" plus every
 * category the repo's entries declare, single-select, horizontally
 * scrollable. Only rendered when the index carries categories at all.
 */
@Composable
private fun CategoryChipsRow(
    categories: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text(stringResource(R.string.extensions_category_all)) },
        )
        categories.forEach { category ->
            FilterChip(
                selected = selected == category,
                onClick = { onSelect(category) },
                label = { Text(category.replaceFirstChar { it.uppercase() }) },
                modifier = Modifier.testTag("extensions:category:$category"),
            )
        }
    }
}

/**
 * One catalog row (Cloudstream's plugin item): avatar, name, version and
 * size, short description, and the trailing per-item action — download
 * until installed, delete after; an update keeps the download affordance
 * as an install-over next to an "Update to vX" chip.
 */
@Composable
private fun CatalogEntryRow(
    entry: RepoPackageEntry,
    installed: Boolean,
    update: Boolean,
    installing: Boolean,
    failed: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonogramAvatar(
            label = displayName(entry),
            seed = entry.id,
        )
        Spacer(Modifier.width(16.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = displayName(entry),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entryMetaLine(entry),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            if (entry.description.isNotBlank()) {
                Text(
                    text = entry.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when {
            installing ->
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.padding(horizontal = 12.dp).size(24.dp),
                )
            update -> {
                LabelChip(
                    text = stringResource(R.string.extensions_catalog_update_to, entry.versionName),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                IconButton(
                    onClick = onInstall,
                    modifier = Modifier.testTag("extensions:update:${entry.id}"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = stringResource(R.string.extensions_install_package),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            installed ->
                IconButton(
                    onClick = onUninstall,
                    modifier = Modifier.testTag("extensions:uninstall:${entry.id}"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = stringResource(R.string.extensions_uninstall),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            else ->
                IconButton(
                    onClick = onInstall,
                    modifier = Modifier.testTag("extensions:install:${entry.id}"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = stringResource(R.string.extensions_install_package),
                        tint =
                            if (failed) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                    )
                }
        }
    }
}

@Composable
private fun CatalogFailed(onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(32.dp),
    ) {
        Text(
            text = stringResource(R.string.extensions_catalog_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onRetry) {
            Text(text = stringResource(R.string.extensions_catalog_retry))
        }
    }
}

@Composable
private fun UninstallEntryDialog(
    entry: RepoPackageEntry,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.extensions_uninstall_title))
        },
        text = {
            Text(text = stringResource(R.string.extensions_uninstall_body, displayName(entry)))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.extensions_uninstall_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.extensions_uninstall_cancel))
            }
        },
    )
}
