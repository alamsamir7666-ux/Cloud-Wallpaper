pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Cloudimage"

include(":app")
include(":core:model")
include(":core:database")
include(":core:datastore")
include(":core:network")
include(":core:search")
include(":core:data")
include(":core:designsystem")
include(":core:muzei")
include(":core:testing")
include(":extensions:core")
include(":fixture:demo-provider")
include(":providers:wallhaven")
// v1.2.25: the WallpaperCave provider module is back in the tree to build
// its fixed package (1.2.1 — preview-family thumbs) for the extension
// repository; the app itself does not depend on it.
include(":providers:wallpapercave")
include(":feature:browse")
include(":feature:browser")
include(":feature:detail")
include(":feature:extensions")
include(":feature:library")
include(":feature:search")
include(":feature:settings")
include(":provider:api")
