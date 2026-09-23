package com.cloudimage.app.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.ui.graphics.vector.ImageVector
import com.cloudimage.app.R
import com.cloudimage.feature.browse.BrowseDestination
import com.cloudimage.feature.extensions.ExtensionsDestination

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
    EXTENSIONS(
        route = ExtensionsDestination.route,
        labelRes = R.string.tab_extensions,
        icon = Icons.Rounded.Extension,
    ),
}
