package com.cloudimage.app.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.ui.graphics.vector.ImageVector
import com.cloudimage.app.R
import com.cloudimage.feature.browse.BrowseDestination
import com.cloudimage.feature.extensions.ExtensionsDestination
import com.cloudimage.feature.library.LibraryDestination
import com.cloudimage.feature.settings.SettingsDestination

enum class TopLevelDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    BROWSE(
        route = BrowseDestination.route,
        labelRes = R.string.tab_browse,
        icon = Icons.Rounded.Wallpaper,
    ),
    LIBRARY(
        route = LibraryDestination.route,
        labelRes = R.string.tab_library,
        icon = Icons.Rounded.PhotoLibrary,
    ),
    EXTENSIONS(
        route = ExtensionsDestination.route,
        labelRes = R.string.tab_extensions,
        icon = Icons.Rounded.Extension,
    ),
    SETTINGS(
        route = SettingsDestination.route,
        labelRes = R.string.tab_settings,
        icon = Icons.Rounded.Settings,
    ),
}
