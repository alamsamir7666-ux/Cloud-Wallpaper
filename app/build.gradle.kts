import com.cloudimage.buildlogic.SyncBundledExtensionsTask

// Release signing material arrives via environment variables (CI secrets or
// a local shell); nothing key-shaped is ever committed.
val releaseStoreFile = providers.environmentVariable("CLOUDIMAGE_STORE_FILE")
val releaseStorePassword = providers.environmentVariable("CLOUDIMAGE_STORE_PASSWORD")
val releaseKeyAlias = providers.environmentVariable("CLOUDIMAGE_KEY_ALIAS")
val releaseKeyPassword = providers.environmentVariable("CLOUDIMAGE_KEY_PASSWORD")
val hasReleaseSigning =
    listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { it.isPresent }

plugins {
    id("cloudimage.android.application")
    id("cloudimage.android.compose")
    id("cloudimage.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.cloudimage.app"

    defaultConfig {
        applicationId = "com.cloudimage.app"
        versionCode = 13
        versionName = "1.0.12"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Must be declared before buildTypes: the release type below looks the
    // config up by name while the DSL executes top to bottom.
    signingConfigs {
        // Signing material arrives via environment variables (CI secrets or
        // a local shell); nothing key-shaped is ever committed.
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile.get())
                storePassword = releaseStorePassword.get()
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPassword.get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

// Consumes the packaged (dexed) provider zips of every bundled provider and
// stages them, plus a generated bundled.json manifest with ids + sha256s,
// into the app's assets — a fresh install reconciles them through the
// normal engine at first start.
//
// The task class lives in build-logic (Gradle 9 cannot instantiate task
// classes declared inside build scripts) and exposes its output as a
// DirectoryProperty so it can be wired through the Variant API — AGP 9
// forbids Provider instances on the legacy SourceSet DSL, and
// addGeneratedSourceDirectory carries the task dependency to asset merging
// itself, so the old merge/lint `dependsOn` matching block is gone.
val bundledExtensions =
    configurations.create("bundledExtensions") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }

val syncBundledExtensions =
    tasks.register<SyncBundledExtensionsTask>("syncBundledExtensions") {
        packages.from(bundledExtensions)
        output.set(layout.buildDirectory.dir("generated/bundledExtensions"))
    }

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(syncBundledExtensions) { it.output }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:network"))
    implementation(project(":core:data"))
    implementation(project(":core:muzei"))
    implementation(project(":extensions:core"))
    implementation(project(":feature:browse"))
    implementation(project(":feature:detail"))
    implementation(project(":feature:extensions"))
    implementation(project(":feature:library"))
    implementation(project(":feature:settings"))
    implementation(project(":provider:api"))

    bundledExtensions(project(mapOf("path" to ":providers:wallhaven", "configuration" to "extensionPackage")))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)

    testImplementation(project(":core:testing"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.datastore.preferences)
    testImplementation(libs.junit)
}
