package com.cloudimage.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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

@Composable
fun CloudimageNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
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
            )
        }
        composable(TopLevelDestination.EXTENSIONS.route) {
            ExtensionsScreen()
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
