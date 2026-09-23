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
include(":core:data")
include(":core:testing")
include(":extensions:core")
include(":fixture:demo-provider")
include(":providers:wallhaven")
include(":providers:unsplash")
include(":providers:pexels")
include(":providers:pixabay")
include(":feature:browse")
include(":feature:detail")
include(":feature:extensions")
include(":provider:api")
