package com.cloudimage.feature.extensions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Verified
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cloudimage.extensions.core.ExtensionStatus
import com.cloudimage.extensions.core.InstalledExtension
import com.cloudimage.extensions.core.RepoError
import com.cloudimage.extensions.core.RepoPackageEntry
import com.cloudimage.extensions.core.StoredRepo
import kotlinx.coroutines.flow.collectLatest

/**
 * The extension manager: everything installed through the engine plus the
 * user's repositories with their installable catalogs, and API keys for
 * sources that need them.
 */
@Composable
fun ExtensionsScreen(
    modifier: Modifier = Modifier,
    viewModel: ExtensionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingUninstall by remember { mutableStateOf<InstalledExtension?>(null) }
    var pendingRemoveRepo by remember { mutableStateOf<StoredRepo?>(null) }
    var pendingKeyProvider by remember { mutableStateOf<com.cloudimage.core.data.repository.SourceInfo?>(null) }
    var showAddRepo by remember { mutableStateOf(false) }

    val addedMessage = stringResource(R.string.extensions_repo_added)
    val keySavedMessage = stringResource(R.string.extensions_key_saved)
    val installedMessage = stringResource(R.string.extensions_installed_snackbar)
    val installFailedMessage = stringResource(R.string.extensions_install_failed_snackbar)
    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is ExtensionsEvent.RepoAdded -> snackbarHostState.showSnackbar(addedMessage)
                is ExtensionsEvent.Installed ->
                    snackbarHostState.showSnackbar(installedMessage.format(event.name))
                is ExtensionsEvent.InstallFailed ->
                    snackbarHostState.showSnackbar(installFailedMessage.format(event.reason))
                is ExtensionsEvent.KeySaved -> snackbarHostState.showSnackbar(keySavedMessage)
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
                ManagerList(
                    state = state,
                    onUninstall = { pendingUninstall = it },
                    onRemoveRepo = { pendingRemoveRepo = it },
                    onInstall = { repo, entry -> viewModel.installPackage(repo, entry) },
                    onKey = { pendingKeyProvider = it },
                )
            }
        }
    }

    pendingUninstall?.let { extension ->
        UninstallDialog(
            extension = extension,
            onConfirm = {
                viewModel.uninstall(extension)
                pendingUninstall = null
            },
            onDismiss = { pendingUninstall = null },
        )
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

    pendingKeyProvider?.let { source ->
        ApiKeyDialog(
            source = source,
            onConfirm = { key ->
                viewModel.saveApiKey(source.id, key)
                pendingKeyProvider = null
            },
            onDismiss = { pendingKeyProvider = null },
        )
    }

    if (showAddRepo) {
        AddRepoDialog(
            adding = state.addingRepo,
            error = state.repoError,
            onConfirm = {
                viewModel.addRepo(it)
                showAddRepo = false
            },
            onDismiss = { showAddRepo = false },
        )
    }
}

@Composable
private fun ManagerList(
    state: ExtensionsUiState,
    onUninstall: (InstalledExtension) -> Unit,
    onRemoveRepo: (StoredRepo) -> Unit,
    onInstall: (StoredRepo, RepoPackageEntry) -> Unit,
    onKey: (com.cloudimage.core.data.repository.SourceInfo) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Text(
                text = stringResource(R.string.extensions_title),
                style = MaterialTheme.typography.titleLarge,
            )
        }
        item {
            SectionLabel(text = stringResource(R.string.extensions_installed_section))
        }
        if (state.extensions.isEmpty()) {
            item {
                EmptyState()
            }
        } else {
            items(
                state.extensions,
                key = { it.id },
            ) { extension ->
                ExtensionRow(
                    extension = extension,
                    needsKey = state.sources.firstOrNull { it.id == extension.manifest?.id }?.requiresApiKey == true,
                    hasKey = extension.manifest?.id in state.keyedProviders,
                    loadFailure = extension.manifest?.id?.let { state.loadFailures[it] },
                    onKey = {
                        state.sources
                            .firstOrNull { it.id == extension.manifest?.id }
                            ?.let(onKey)
                    },
                    onUninstall = { onUninstall(extension) },
                )
            }
        }
        item {
            SectionLabel(text = stringResource(R.string.extensions_repos_section))
        }
        if (state.repos.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.extensions_repos_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(
                state.repos,
                key = { it.id },
            ) { repo ->
                RepoSection(
                    repo = repo,
                    catalog = state.catalogs[repo.id],
                    installedIds = state.extensions.mapNotNull { it.manifest?.id }.toSet(),
                    installing = state.installing,
                    failed = state.failed,
                    onInstall = { onInstall(repo, it) },
                    onRemove = { onRemoveRepo(repo) },
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun ExtensionRow(
    extension: InstalledExtension,
    needsKey: Boolean,
    hasKey: Boolean,
    loadFailure: String?,
    onKey: () -> Unit,
    onUninstall: () -> Unit,
) {
    val manifest = extension.manifest
    ProviderCard {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (manifest?.id == "cloudimage.wallhaven") Icons.Rounded.Verified else Icons.Rounded.Extension,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(28.dp),
            )
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = manifest?.name ?: extension.id,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text =
                        manifest
                            ?.let { "${it.id} · v${it.versionName}" }
                            ?: extension.id,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChip(status = extension.status)
                    manifest?.let { LabelChip(text = stringResource(R.string.extensions_api_version, it.apiVersion)) }
                    if (needsKey && !hasKey) {
                        LabelChip(
                            text = stringResource(R.string.extensions_key_needed),
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
                loadFailure?.let { reason ->
                    Text(
                        text = stringResource(R.string.extensions_load_failed, reason),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (needsKey) {
                IconButton(onClick = onKey) {
                    Icon(
                        imageVector = Icons.Rounded.Key,
                        contentDescription = stringResource(R.string.extensions_set_key),
                        tint =
                            if (hasKey) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
            }
            IconButton(onClick = onUninstall) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = stringResource(R.string.extensions_uninstall),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun RepoSection(
    repo: StoredRepo,
    catalog: List<RepoPackageEntry>?,
    installedIds: Set<String>,
    installing: Set<String>,
    failed: Set<String>,
    onInstall: (RepoPackageEntry) -> Unit,
    onRemove: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RepoHeader(
            repo = repo,
            catalogPending = catalog == null,
            onRemove = onRemove,
        )
        catalog.orEmpty().forEach { entry ->
            CatalogRow(
                entry = entry,
                installed = entry.id in installedIds,
                installing = entry.id in installing,
                failed = entry.id in failed,
                onInstall = { onInstall(entry) },
            )
        }
    }
}

@Composable
private fun RepoHeader(
    repo: StoredRepo,
    catalogPending: Boolean,
    onRemove: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = repo.name,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = repo.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (catalogPending) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(24.dp).padding(end = 8.dp),
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = stringResource(R.string.extensions_remove_repo),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun CatalogRow(
    entry: RepoPackageEntry,
    installed: Boolean,
    installing: Boolean,
    failed: Boolean,
    onInstall: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.id.substringAfterLast('.').replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text =
                        stringResource(
                            R.string.extensions_catalog_entry_details,
                            entry.versionName,
                            entry.author.ifBlank { "-" },
                            formatSize(entry.sizeBytes),
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                installing -> CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                installed -> LabelChip(text = stringResource(R.string.extensions_catalog_installed))
                else ->
                    IconButton(onClick = onInstall) {
                        Icon(
                            imageVector = if (failed) Icons.Rounded.DeleteOutline else Icons.Rounded.Download,
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
}

@Composable
private fun formatSize(bytes: Long): String = "%.1f MB".format(bytes / 1024f / 1024f)

@Composable
private fun StatusChip(status: ExtensionStatus) {
    val label =
        stringResource(
            when (status) {
                ExtensionStatus.READY -> R.string.extensions_status_ready
                ExtensionStatus.CORRUPTED -> R.string.extensions_status_corrupted
                ExtensionStatus.UNTRUSTED -> R.string.extensions_status_untrusted
            },
        )
    if (status == ExtensionStatus.READY) {
        LabelChip(
            text = label,
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    } else {
        LabelChip(
            text = label,
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun LabelChip(
    text: String,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ProviderCard(content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        content()
    }
}

@Composable
private fun EmptyState() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Extension,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
        Text(
            text = stringResource(R.string.extensions_empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.extensions_empty_body),
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
private fun ApiKeyDialog(
    source: com.cloudimage.core.data.repository.SourceInfo,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var key by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.extensions_key_title, source.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = stringResource(R.string.extensions_key_body))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text(stringResource(R.string.extensions_key_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(key.trim()) },
                enabled = key.isNotBlank(),
            ) {
                Text(text = stringResource(R.string.extensions_key_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.extensions_key_cancel))
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

@Composable
private fun UninstallDialog(
    extension: InstalledExtension,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.extensions_uninstall_title))
        },
        text = {
            val name = extension.manifest?.name ?: extension.id
            Text(text = stringResource(R.string.extensions_uninstall_body, name))
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
