import java.security.MessageDigest
import java.util.zip.ZipFile

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
        versionCode = 4
        versionName = "1.0.3"
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

    // The bundled default providers live in a generated assets dir that the
    // sync task below fills from the provider modules' packaged zips.
    sourceSets {
        getByName("main") {
            assets.srcDir(layout.buildDirectory.dir("generated/bundledExtensions"))
        }
    }
}

// Consumes the packaged (dexed) provider zips of every bundled provider and
// stages them, plus a generated bundled.json manifest with ids + sha256s,
// into the app's assets — a fresh install reconciles them through the
// normal engine at first start.
val bundledExtensions: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

val syncBundledExtensions by tasks.registering(Sync::class) {
    from(bundledExtensions)
    into(layout.buildDirectory.dir("generated/bundledExtensions"))
    doLast {
        val dir = layout.buildDirectory.dir("generated/bundledExtensions").get().asFile
        val zips = dir.listFiles { file -> file.extension == "zip" }.orEmpty()
        val manifest =
            zips.joinToString(prefix = "[\n", separator = ",\n", postfix = "\n]") { zip ->
                val id = readExtensionId(zip)
                val sha = sha256(zip)
                """  {"id": "$id", "fileName": "${zip.name}", "sha256": "$sha"}"""
            }
        File(dir, "bundled.json").writeText(manifest)
    }
}

tasks.matching {
    (it.name.contains("merge", ignoreCase = true) && it.name.endsWith("Assets")) ||
        it.name.startsWith("lintVitalAnalyze") ||
        it.name.startsWith("generateReleaseLintVitalReportModel")
}.configureEach {
    dependsOn(syncBundledExtensions)
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:network"))
    implementation(project(":core:data"))
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
    implementation(libs.hilt.navigation.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)

    testImplementation(project(":core:testing"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.datastore.preferences)
    testImplementation(libs.junit)
}

/** Reads the `id` field out of the extension.json inside a package zip. */
fun readExtensionId(zip: File): String {
    val text =
        ZipFile(zip).use { archive ->
            val entry = archive.getEntry("extension.json") ?: error("${zip.name} has no extension.json")
            archive.getInputStream(entry).bufferedReader().readText()
        }
    return text.substringAfter("\"id\"").substringAfter(':').substringAfter('"').substringBefore('"')
}

/** Hex sha256 of a file, matching the engine's checksum format. */
fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
