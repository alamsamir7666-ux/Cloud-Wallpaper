package com.cloudimage.app.navigation

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.cloudimage.core.model.Wallpaper
import com.cloudimage.feature.browse.BrowseScreen
import com.cloudimage.feature.detail.DetailDestination
import com.cloudimage.feature.detail.DetailScreen
import com.cloudimage.feature.extensions.ExtensionsScreen
import com.cloudimage.feature.library.LibraryScreen
import com.cloudimage.feature.settings.SettingsScreen

@Composable
fun CloudimageNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    NavHost(
        navController = navController,
        startDestination = TopLevelDestination.BROWSE.route,
        modifier = modifier,
    ) {
        composable(TopLevelDestination.BROWSE.route) {
            BrowseScreen(
                onWallpaperClick = { wallpaper: Wallpaper ->
                    navController.navigate(DetailDestination.createRoute(wallpaper))
                },
                onOpenExtensions = {
                    // Same navigation contract as the bottom bar, so the
                    // browse tab's state survives the round trip.
                    navController.navigate(TopLevelDestination.EXTENSIONS.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
        }
        composable(TopLevelDestination.LIBRARY.route) {
            LibraryScreen(
                onWallpaperClick = { wallpaper: Wallpaper ->
                    navController.navigate(DetailDestination.createRoute(wallpaper))
                },
            )
        }
        composable(TopLevelDestination.EXTENSIONS.route) {
            ExtensionsScreen()
        }
        composable(TopLevelDestination.SETTINGS.route) {
            SettingsScreen(
                onOpenUrl = { url ->
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                },
            )
        }
        composable(
            route = DetailDestination.route,
            arguments =
                listOf(
                    navArgument(DetailDestination.arg) { type = NavType.StringType },
                ),
        ) { backStackEntry ->
            DetailScreen(
                onBack = { navController.popBackStack() },
            )
        }
    }
}
