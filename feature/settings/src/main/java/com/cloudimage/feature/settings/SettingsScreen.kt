package com.cloudimage.feature.settings

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.HistoryEdu
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cloudimage.core.data.repository.ApplyError
import com.cloudimage.core.model.AppUpdate
import com.cloudimage.core.model.RotationSettings
import com.cloudimage.core.model.RotationTarget

/** Where the "Source code" row points; also used by onboarding-free deep links. */
private const val SOURCE_URL = "https://github.com/alamsamir7666-ux/Cloud-Wallpaper"

/** Grid column choices offered in the appearance section. */
private val GRID_COLUMN_CHOICES = listOf(1, 2, 3, 4)

/**
 * All user-facing knobs in one place: content safety, theming, grid
 * density, a data overview, and about/credits.
 *
 * Every control writes to DataStore and lets the reactive flow repaint
 * the row — no local optimistic state to get out of sync.
 */
@Composable
fun SettingsScreen(
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        contentPadding = PaddingValues(bottom = 24.dp),
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .testTag("settings:list"),
    ) {
        item {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.titleLarge,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 20.dp, top = 16.dp, bottom = 8.dp),
            )
        }

        item { SectionHeader(R.string.settings_section_content) }
        item {
            SwitchRow(
                icon = Icons.Rounded.HealthAndSafety,
                titleRes = R.string.settings_sfw_title,
                bodyRes = R.string.settings_sfw_body,
                checked = state.sfwOnly,
                onCheckedChange = viewModel::setSfwOnly,
            )
        }

        item { SectionHeader(R.string.settings_section_appearance) }
        item {
            SwitchRow(
                icon = Icons.Rounded.Palette,
                titleRes = R.string.settings_colors_title,
                bodyRes = R.string.settings_colors_body,
                checked = state.dynamicColorsEnabled,
                onCheckedChange = viewModel::setDynamicColorsEnabled,
            )
        }
        item { GridColumnsRow(selected = state.gridColumns, onSelect = viewModel::setGridColumns) }

        item { SectionHeader(R.string.settings_section_rotation) }
        item {
            SwitchRow(
                icon = Icons.Rounded.Autorenew,
                titleRes = R.string.settings_rotation_title,
                bodyRes = R.string.settings_rotation_body,
                checked = state.rotation.enabled,
                onCheckedChange = viewModel::setRotationEnabled,
                modifier = Modifier.testTag("settings:rotation"),
            )
        }
        if (state.rotation.enabled) {
            if (state.favoriteCount == 0) {
                item { RotationEmptyHint() }
            }
            item {
                RotationIntervalRow(
                    selected = state.rotation.intervalMinutes,
                    onSelect = viewModel::setRotationInterval,
                )
            }
            item {
                RotationTargetRow(
                    selected = state.rotation.target,
                    onSelect = viewModel::setRotationTarget,
                )
            }
            item {
                SwitchRow(
                    icon = Icons.Rounded.Wifi,
                    titleRes = R.string.settings_rotation_wifi_title,
                    bodyRes = R.string.settings_rotation_wifi_body,
                    checked = state.rotation.wifiOnly,
                    onCheckedChange = viewModel::setRotationWifiOnly,
                )
            }
            item { RotateNowRow(state = state, onRotate = viewModel::rotateNow) }
        }

        item { SectionHeader(R.string.settings_section_data) }
        item {
            ValueRow(
                icon = Icons.Rounded.Favorite,
                titleRes = R.string.settings_favorites_title,
                value = stringResource(R.string.settings_favorites_value, state.favoriteCount),
            )
        }
        item {
            ValueRow(
                icon = Icons.Rounded.HistoryEdu,
                titleRes = R.string.settings_history_title,
                value = stringResource(R.string.settings_history_value, state.historyCount),
            )
        }

        item { SectionHeader(R.string.settings_section_updates) }
        item { UpdateCard(state = state, viewModel = viewModel) }

        item { SectionHeader(R.string.settings_section_about) }
        item {
            ValueRow(
                icon = Icons.Rounded.Info,
                titleRes = R.string.settings_version_title,
                value = state.versionName,
            )
        }
        item {
            LinkRow(
                icon = Icons.Rounded.Code,
                titleRes = R.string.settings_source_title,
                onOpen = { onOpenUrl(SOURCE_URL) },
            )
        }
        item {
            ValueRow(
                icon = Icons.AutoMirrored.Rounded.OpenInNew,
                titleRes = R.string.settings_licenses_title,
                value = stringResource(R.string.settings_licenses_value),
            )
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

/**
 * The in-app updater card: check GitHub Releases, download, and hand off
 * to the system package installer. Opened releases page in a browser
 * stays available for users who prefer sideloading manually.
 */
@Composable
private fun UpdateCard(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    // Auto-check when the section first appears; re-checking on every
    // visit is fine — settings opens are rare and user-driven.
    LaunchedEffect(Unit) { viewModel.checkForUpdate() }

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).testTag("settings:update"),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            when (val update = state.update) {
                UpdateState.Idle, UpdateState.Checking -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = stringResource(R.string.settings_update_checking),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }

                UpdateState.UpToDate -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.SystemUpdate,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = stringResource(R.string.settings_update_up_to_date, state.versionName),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }

                is UpdateState.Available -> {
                    AvailableUpdate(
                        update = update.update,
                        onDownload = viewModel::downloadAndInstall,
                    )
                }

                UpdateState.Downloading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = stringResource(R.string.settings_update_downloading),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }

                is UpdateState.Failed -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = update.error.message(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp).weight(1f),
                        )
                        OutlinedButton(onClick = viewModel::checkForUpdate) {
                            Text(stringResource(R.string.settings_update_retry))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AvailableUpdate(
    update: AppUpdate,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val size = remember(update.apkSizeBytes) { Formatter.formatShortFileSize(context, update.apkSizeBytes) }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.SystemUpdate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = stringResource(R.string.settings_update_available_title, update.versionName),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        Text(
            text = stringResource(R.string.settings_update_available_body, size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onDownload, modifier = Modifier.fillMaxWidth().height(44.dp)) {
            Text(stringResource(R.string.settings_update_download))
        }
    }
}

@Composable
private fun UpdateError.message(): String =
    when (this) {
        UpdateError.HTTP -> stringResource(R.string.settings_update_error_http)
        UpdateError.TIMEOUT -> stringResource(R.string.settings_update_error_timeout)
        UpdateError.OFFLINE -> stringResource(R.string.settings_update_error_offline)
        UpdateError.BAD_DATA -> stringResource(R.string.settings_update_error_bad_data)
        UpdateError.INSTALL -> stringResource(R.string.settings_update_error_install)
    }

@Composable
private fun SectionHeader(
    labelRes: Int,
    modifier: Modifier = Modifier,
) {
    Text(
        text = stringResource(labelRes),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = 20.dp, top = 20.dp, bottom = 6.dp),
    )
}

@Composable
private fun RowIcon(icon: ImageVector) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(8.dp).size(24.dp),
        )
    }
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    titleRes: Int,
    bodyRes: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        RowIcon(icon = icon)
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
        ) {
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(bodyRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun GridColumnsRow(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_grid_title),
            style = MaterialTheme.typography.bodyLarge,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 10.dp),
        ) {
            GRID_COLUMN_CHOICES.forEach { columns ->
                val label = columns.toString()
                FilterChip(
                    selected = columns == selected,
                    onClick = { onSelect(columns) },
                    label = { Text(label) },
                    modifier =
                        Modifier.semantics {
                            contentDescription = "$columns columns"
                        },
                )
            }
        }
    }
}

@Composable
private fun ValueRow(
    icon: ImageVector,
    titleRes: Int,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        RowIcon(icon = icon)
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LinkRow(
    icon: ImageVector,
    titleRes: Int,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        RowIcon(icon = icon)
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun RotationEmptyHint(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.settings_rotation_empty),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RotationIntervalRow(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_rotation_interval_title),
            style = MaterialTheme.typography.bodyLarge,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 10.dp),
        ) {
            RotationSettings.INTERVAL_CHOICES_MINUTES.forEach { minutes ->
                FilterChip(
                    selected = minutes == selected,
                    onClick = { onSelect(minutes) },
                    label = { Text(intervalLabel(minutes)) },
                )
            }
        }
    }
}

@Composable
private fun intervalLabel(minutes: Int): String =
    if (minutes < 60) {
        stringResource(R.string.settings_rotation_interval_minutes, minutes)
    } else {
        stringResource(R.string.settings_rotation_interval_hours, minutes / 60)
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RotationTargetRow(
    selected: RotationTarget,
    onSelect: (RotationTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_rotation_target_title),
            style = MaterialTheme.typography.bodyLarge,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 10.dp),
        ) {
            RotationTarget.entries.forEach { target ->
                FilterChip(
                    selected = target == selected,
                    onClick = { onSelect(target) },
                    label = { Text(target.label()) },
                )
            }
        }
    }
}

@Composable
private fun RotationTarget.label(): String =
    when (this) {
        RotationTarget.HOME -> stringResource(R.string.settings_rotation_target_home)
        RotationTarget.LOCK -> stringResource(R.string.settings_rotation_target_lock)
        RotationTarget.BOTH -> stringResource(R.string.settings_rotation_target_both)
    }

/**
 * The immediate-action row: one tap applies the next saved wallpaper now,
 * with inline feedback of what happened (which one, or why it failed).
 */
@Composable
private fun RotateNowRow(
    state: SettingsUiState,
    onRotate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        OutlinedButton(
            onClick = onRotate,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .testTag("settings:rotation_now"),
        ) {
            if (state.rotateNow is RotateNowState.Running) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp),
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.settings_rotation_now))
        }
        val feedback = state.rotateNow.message()
        if (feedback != null) {
            Text(
                text = feedback,
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (state.rotateNow is RotateNowState.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** Inline feedback under the "rotate now" button; null keeps the row quiet. */
@Composable
private fun RotateNowState.message(): String? =
    when (this) {
        RotateNowState.Idle -> null
        RotateNowState.Running -> stringResource(R.string.settings_rotation_now_running)
        is RotateNowState.Done -> {
            val title = wallpaper.title ?: wallpaper.providerId
            stringResource(R.string.settings_rotation_now_done, title)
        }
        RotateNowState.NoFavorites -> stringResource(R.string.settings_rotation_now_empty)
        is RotateNowState.Failed -> error.message()
    }

@Composable
private fun ApplyError.message(): String =
    when (this) {
        ApplyError.OFFLINE -> stringResource(R.string.settings_rotation_error_offline)
        ApplyError.TIMEOUT -> stringResource(R.string.settings_rotation_error_timeout)
        ApplyError.HTTP -> stringResource(R.string.settings_rotation_error_http)
        ApplyError.DECODE -> stringResource(R.string.settings_rotation_error_decode)
        ApplyError.UNSUPPORTED -> stringResource(R.string.settings_rotation_error_unsupported)
        ApplyError.IO -> stringResource(R.string.settings_rotation_error_io)
    }
