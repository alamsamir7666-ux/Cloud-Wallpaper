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
import com.cloudimage.core.data.viewer.ViewerSession
import com.cloudimage.core.model.Wallpaper
import com.cloudimage.feature.browse.BrowseScreen
import com.cloudimage.feature.detail.DetailDestination
import com.cloudimage.feature.detail.DetailScreen
import com.cloudimage.feature.extensions.ExtensionsDestination
import com.cloudimage.feature.extensions.ExtensionsScreen
import com.cloudimage.feature.extensions.InstalledExtensionsScreen
import com.cloudimage.feature.extensions.RepoDetailScreen
import com.cloudimage.feature.library.LibraryScreen
import com.cloudimage.feature.search.SearchScreen
import com.cloudimage.feature.settings.SettingsScreen

/**
 * The app's navigation graph. Every grid that opens the viewer parks its
 * list and the tapped index in the [ViewerSession] (v1.0.23) right before
 * navigating, so the fullscreen viewer can page through it with sideways
 * swipes.
 */
@Composable
fun CloudimageNavHost(
    navController: NavHostController,
    viewerSession: ViewerSession,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    fun openWallpaper(
        wallpapers: List<Wallpaper>,
        index: Int,
    ) {
        viewerSession.open(wallpapers, index)
        navController.navigate(DetailDestination.createRoute(wallpapers[index]))
    }

    NavHost(
        navController = navController,
        startDestination = TopLevelDestination.BROWSE.route,
        modifier = modifier,
    ) {
        composable(TopLevelDestination.BROWSE.route) {
            BrowseScreen(
                onWallpaperClick = { wallpapers: List<Wallpaper>, index: Int ->
                    openWallpaper(wallpapers, index)
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
                onWallpaperClick = { wallpapers: List<Wallpaper>, index: Int ->
                    openWallpaper(wallpapers, index)
                },
            )
        }
        composable(TopLevelDestination.SEARCH.route) {
            SearchScreen(
                onWallpaperClick = { wallpapers: List<Wallpaper>, index: Int ->
                    openWallpaper(wallpapers, index)
                },
            )
        }
        composable(TopLevelDestination.EXTENSIONS.route) {
            ExtensionsScreen(
                onOpenRepo = { repo ->
                    navController.navigate(ExtensionsDestination.createRepoRoute(repo.id))
                },
                onOpenInstalled = {
                    navController.navigate(ExtensionsDestination.installedRoute)
                },
            )
        }
        composable(
            route = ExtensionsDestination.repoRoute,
            arguments =
                listOf(
                    navArgument(ExtensionsDestination.repoArg) { type = NavType.StringType },
                ),
        ) {
            RepoDetailScreen(onBack = { navController.popBackStack() })
        }
        composable(ExtensionsDestination.installedRoute) {
            InstalledExtensionsScreen(onBack = { navController.popBackStack() })
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
                onOpenWallpaper = { wallpapers: List<Wallpaper>, index: Int ->
                    openWallpaper(wallpapers, index)
                },
            )
        }
    }
}
