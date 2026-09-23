package com.cloudimage.feature.detail

/** Route contract for the detail feature. Pass the provider-side wallpaper id. */
object DetailDestination {
    const val route = "detail/{wallpaperId}"
    const val arg = "wallpaperId"

    fun createRoute(wallpaperId: String) = "detail/$wallpaperId"
}
