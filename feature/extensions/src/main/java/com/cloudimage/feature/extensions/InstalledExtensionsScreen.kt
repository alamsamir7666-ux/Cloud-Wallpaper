package com.cloudimage.feature.extensions

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cloudimage.core.data.repository.SourceInfo
import com.cloudimage.core.designsystem.CompactSwitch
import com.cloudimage.extensions.core.InstalledExtension
import kotlinx.coroutines.flow.collectLatest

/**
 * The installed extensions list (v1.0.20), reached from the counts bar:
 * every installed extension with its per-source enablement switch, API
 * key flow for key-based sources, load diagnostics, and uninstall.
 */
@Composable
fun InstalledExtensionsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: InstalledExtensionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingUninstall by remember { mutableStateOf<InstalledExtension?>(null) }
    var pendingKeyProvider by remember { mutableStateOf<SourceInfo?>(null) }

    val keySavedMessage = stringResource(R.string.extensions_key_saved)
    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is InstalledExtensionsEvent.KeySaved -> snackbarHostState.showSnackbar(keySavedMessage)
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
                    text = stringResource(R.string.extensions_installed_title),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                placeholder = { Text(stringResource(R.string.extensions_search_installed)) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setQuery("") }) {
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
                        .padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
            )
            if (state.loading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                val visible = state.visibleExtensions()
                LazyColumn(
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 24.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (visible.isEmpty()) {
                        item { InstalledEmptyState(hasQuery = state.query.isNotBlank()) }
                    } else {
                        items(
                            visible,
                            key = { it.id },
                        ) { extension ->
                            InstalledRow(
                                extension = extension,
                                disabled = extension.manifest?.id in state.disabledSources,
                                needsKey =
                                    state.sources
                                        .firstOrNull { it.id == extension.manifest?.id }
                                        ?.requiresApiKey == true,
                                hasKey = extension.manifest?.id in state.keyedProviders,
                                loadFailure = extension.manifest?.id?.let { state.loadFailures[it] },
                                onToggle = { enabled -> viewModel.setSourceEnabled(extension, enabled) },
                                onKey = {
                                    state.sources
                                        .firstOrNull { it.id == extension.manifest?.id }
                                        ?.let { pendingKeyProvider = it }
                                },
                                onUninstall = { pendingUninstall = extension },
                            )
                        }
                    }
                }
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
}

/**
 * One installed extension — the Cloudstream plugin row restyled on this
 * app's palette: avatar, name, id · version, status chips, and the
 * per-source controls (enable switch, API key, uninstall).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InstalledRow(
    extension: InstalledExtension,
    disabled: Boolean,
    needsKey: Boolean,
    hasKey: Boolean,
    loadFailure: String?,
    onToggle: (Boolean) -> Unit,
    onKey: () -> Unit,
    onUninstall: () -> Unit,
) {
    val manifest = extension.manifest
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonogramAvatar(
            label = manifest?.name ?: extension.id,
            seed = extension.id,
        )
        Spacer(Modifier.width(16.dp))
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .alpha(rowAlpha(disabled)),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = manifest?.name ?: extension.id,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = manifest?.let { "${it.id} · v${it.versionName}" } ?: extension.id,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                StatusChip(status = extension.status)
                manifest?.let { LabelChip(text = stringResource(R.string.extensions_api_version, it.apiVersion)) }
                if (disabled) {
                    LabelChip(
                        text = stringResource(R.string.extensions_disabled_chip),
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
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
        if (manifest != null) {
            CompactSwitch(
                checked = !disabled,
                onCheckedChange = onToggle,
                modifier =
                    Modifier
                        .padding(start = 4.dp)
                        .testTag("extensions:toggle:${manifest.id}"),
            )
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

@Composable
private fun InstalledEmptyState(hasQuery: Boolean) {
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
            text =
                stringResource(
                    if (hasQuery) {
                        R.string.extensions_catalog_no_matches
                    } else {
                        R.string.extensions_empty_title
                    },
                ),
            style = MaterialTheme.typography.titleMedium,
        )
        if (!hasQuery) {
            Text(
                text = stringResource(R.string.extensions_empty_body_installed),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ApiKeyDialog(
    source: SourceInfo,
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
