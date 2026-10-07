package com.cloudimage.feature.extensions

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cloudimage.extensions.core.RepoPackageEntry
import kotlinx.coroutines.flow.collectLatest

/**
 * One bundle's member list (v1.2.2): the resolved members in bundle order
 * with checkbox selection for the bulk install, a walking spinner while
 * the batch runs one member at a time, per-row install-over updates,
 * per-row uninstall, and inert rows for ids the catalog no longer
 * carries. The pinned bottom bar installs the checked members or reports
 * the bundle complete.
 */
@Composable
fun BundleDetailScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BundleDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingUninstall by remember { mutableStateOf<RepoPackageEntry?>(null) }

    val installedMessage = stringResource(R.string.extensions_installed_snackbar)
    val updatedMessage = stringResource(R.string.extensions_updated_snackbar)
    val installFailedMessage = stringResource(R.string.extensions_install_failed_snackbar)
    val uninstalledMessage = stringResource(R.string.extensions_uninstalled_snackbar)
    val bundleInstalledMessage = stringResource(R.string.extensions_bundle_installed_snackbar)
    val bundleInstalledFailuresMessage = stringResource(R.string.extensions_bundle_installed_failures_snackbar)
    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is RepoDetailEvent.Installed -> snackbarHostState.showSnackbar(installedMessage.format(event.name))
                is RepoDetailEvent.Updated -> snackbarHostState.showSnackbar(updatedMessage.format(event.name))
                is RepoDetailEvent.InstallFailed ->
                    snackbarHostState.showSnackbar(installFailedMessage.format(event.reason))
                is RepoDetailEvent.Uninstalled -> snackbarHostState.showSnackbar(uninstalledMessage.format(event.name))
                is RepoDetailEvent.BundleInstalled ->
                    if (event.failedCount == 0) {
                        snackbarHostState.showSnackbar(bundleInstalledMessage.format(event.bundleName, event.installedCount))
                    } else {
                        snackbarHostState.showSnackbar(
                            bundleInstalledFailuresMessage.format(event.bundleName, event.installedCount, event.failedCount),
                        )
                    }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (state.status == BundleCatalogStatus.READY) {
                BundleBottomBar(
                    selectedCount = state.selected.size,
                    installing = state.installing.isNotEmpty(),
                    complete = state.complete,
                    totalMembers = state.totalMembers,
                    onInstallSelected = viewModel::installSelected,
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
            RepoDetailToolbar(
                title = state.bundle?.let { displayBundleName(it) } ?: state.requestedBundleId,
                loading = state.status == BundleCatalogStatus.LOADING,
                onBack = onBack,
                onRefresh = viewModel::refresh,
            )
            when (state.status) {
                BundleCatalogStatus.LOADING ->
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                BundleCatalogStatus.FAILED -> CatalogFailed(onRetry = viewModel::refresh)
                BundleCatalogStatus.MISSING -> BundleMissing(onRetry = viewModel::refresh)
                BundleCatalogStatus.READY -> {
                    LazyColumn(
                        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 24.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        val bundle = state.bundle
                        if (bundle != null && bundle.description.isNotBlank()) {
                            item(key = "bundle-description") {
                                Text(
                                    text = bundle.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                                )
                            }
                        }
                        if (state.entries.isEmpty() && state.missingIds.isEmpty()) {
                            item(key = "bundle-empty") {
                                Text(
                                    text = stringResource(R.string.extensions_bundle_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(24.dp),
                                )
                            }
                        }
                        items(
                            state.entries,
                            key = { it.id },
                        ) { entry ->
                            BundleMemberRow(
                                entry = entry,
                                installed = entry.id in state.installedManifests,
                                update = state.updateAvailable(entry),
                                selected = entry.id in state.selected,
                                installing = entry.id in state.installing,
                                failed = entry.id in state.failedInstalls,
                                selectionEnabled = state.installing.isEmpty(),
                                onToggleSelected = { viewModel.toggleSelected(entry.id) },
                                onInstall = { viewModel.installMember(entry) },
                                onUninstall = { pendingUninstall = entry },
                            )
                        }
                        items(
                            state.missingIds,
                            key = { "missing:$it" },
                        ) { id ->
                            MissingMemberRow(id = id)
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
                viewModel.uninstallMember(entry)
                pendingUninstall = null
            },
            onDismiss = { pendingUninstall = null },
        )
    }
}

/**
 * The pinned bulk-install bar: one button for the checked members while
 * anything is missing, and the completion message once the bundle is
 * whole. Frozen while a batch is in flight.
 */
@Composable
private fun BundleBottomBar(
    selectedCount: Int,
    installing: Boolean,
    complete: Boolean,
    totalMembers: Int,
    onInstallSelected: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (complete) {
                Text(
                    text = stringResource(R.string.extensions_bundle_all_installed, totalMembers),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Button(
                    onClick = onInstallSelected,
                    enabled = selectedCount > 0 && !installing,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag("extensions:bundle-install-selected"),
                ) {
                    Text(text = stringResource(R.string.extensions_bundle_install_selected, selectedCount))
                }
            }
        }
    }
}

/**
 * One member row: a leading checkbox while uninstalled (the bulk
 * selection) or a check mark once installed, the catalog-style texts, and
 * the trailing affordance — single install, update install-over, or
 * uninstall.
 */
@Composable
private fun BundleMemberRow(
    entry: RepoPackageEntry,
    installed: Boolean,
    update: Boolean,
    selected: Boolean,
    installing: Boolean,
    failed: Boolean,
    selectionEnabled: Boolean,
    onToggleSelected: () -> Unit,
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
        Box(
            modifier = Modifier.size(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (installed) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = stringResource(R.string.extensions_bundle_member_installed),
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(24.dp),
                )
            } else {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelected() },
                    enabled = selectionEnabled,
                    modifier = Modifier.testTag("extensions:bundle-toggle:${entry.id}"),
                )
            }
        }
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
                    modifier = Modifier.testTag("extensions:bundle-update:${entry.id}"),
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
                    modifier = Modifier.testTag("extensions:bundle-uninstall:${entry.id}"),
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
                    modifier = Modifier.testTag("extensions:bundle-install:${entry.id}"),
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

/** A bundle-declared id the catalog no longer carries — visible, dimmed, inert. */
@Composable
private fun MissingMemberRow(id: String) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .alpha(rowAlpha(disabled = true)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonogramAvatar(
            label = id,
            seed = id,
        )
        Spacer(Modifier.width(16.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = id,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.extensions_bundle_member_missing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** The bundle vanished from the index — the repo removed or renamed it. */
@Composable
private fun BundleMissing(onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(32.dp),
    ) {
        Text(
            text = stringResource(R.string.extensions_bundle_missing),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onRetry) {
            Text(text = stringResource(R.string.extensions_catalog_retry))
        }
    }
}
