package com.cloudimage.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.bundling.Zip
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByName
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import java.io.File

/**
 * Convention for the wallpaper provider modules (`:providers:*`).
 *
 * A provider is a plain Kotlin JVM library compiled against `:provider:api`.
 * This plugin adds `packageExtension`, which turns the compiled jar into a
 * distributable extension package:
 *
 * 1. `d8` (from the `com.android.tools:r8` artifact) dexes the module jar,
 *    with the runtime classpath on `--classpath` so the contract's classes
 *    stay out of the payload — the host supplies them at runtime, exactly
 *    like the fixture in the engine tests;
 * 2. the dex plus the module's root `extension.json` manifest are zipped
 *    into `build/outputs/extension/<id>.zip`, where the id is read from the
 *    manifest itself;
 * 3. the zip is published through the outgoing `extensionPackage`
 *    configuration so the app (bundled defaults) and the repo publisher can
 *    consume it without knowing the path.
 */
class CloudProviderConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")
            pluginManager.apply("org.jlleitschuh.gradle.ktlint")
            configureKotlin()

            val outgoing: Configuration =
                configurations.create("extensionPackage") {
                    isCanBeConsumed = true
                    isCanBeResolved = false
                }
            val d8Runtime: Configuration =
                configurations.create("d8Runtime") {
                    isCanBeConsumed = false
                    isCanBeResolved = true
                }

            dependencies {
                "implementation"(project(":provider:api"))
                "testImplementation"(libs.findLibrary("junit").get())
                "testImplementation"(libs.findLibrary("kotlinx-coroutines-test").get())
                "d8Runtime"(libs.findLibrary("r8").get())
            }

            val moduleManifest = layout.projectDirectory.file("extension.json")
            val dexOut = layout.buildDirectory.dir("intermediates/extension-dex")
            val packageOut = layout.buildDirectory.dir("outputs/extension")
            val packageId = manifestId(moduleManifest.asFile)
            val jarTask = tasks.named<org.gradle.jvm.tasks.Jar>("jar")

            tasks.register<Exec>("dexProvider") {
                group = "extension"
                description = "Dexes the provider jar with d8."
                dependsOn(jarTask)
                inputs.files(jarTask.map { it.outputs.files })
                inputs.files(configurations.getByName("runtimeClasspath"))
                outputs.dir(dexOut)
                doFirst {
                    val androidJar = findAndroidJar(rootProject)
                    val jar = jarTask.get().outputs.files.files.single { it.extension == "jar" }
                    commandLine(
                        "java",
                        "-cp",
                        d8Runtime.resolve().joinToString(File.pathSeparator) { it.absolutePath },
                        "com.android.tools.r8.D8",
                        "--release",
                        "--min-api",
                        "26",
                        "--lib",
                        androidJar.absolutePath,
                        "--output",
                        dexOut.get().asFile.absolutePath,
                    )
                    configurations.getByName("runtimeClasspath").resolve().forEach {
                        args("--classpath", it.absolutePath)
                    }
                    args(jar.absolutePath)
                }
            }

            tasks.register<Zip>("packageExtension") {
                group = "extension"
                description = "Packages the dexed provider plus its manifest into a distributable zip."
                dependsOn(tasks.named("dexProvider"))
                from(dexOut)
                from(moduleManifest)
                destinationDirectory.set(packageOut)
                archiveFileName.set("$packageId.zip")
            }

            artifacts.add("extensionPackage", tasks.named<Zip>("packageExtension"))
        }
    }

    private fun manifestId(manifestFile: File): String =
        manifestFile.readText()
            .substringAfter("\"id\"")
            .substringAfter(':')
            .substringAfter('"')
            .substringBefore('"')
            .ifBlank { error("extension.json must declare a non-blank id") }
}

/** Locates android.jar in the highest installed platform for d8's `--lib`. */
internal fun findAndroidJar(rootProject: Project): File {
    val candidates =
        sequence {
            listOfNotNull(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT")).forEach { yield(File(it)) }
            val properties = File(rootProject.projectDir, "local.properties")
            if (properties.exists()) {
                properties.readLines()
                    .firstOrNull { it.startsWith("sdk.dir=") }
                    ?.substringAfter('=')
                    ?.let { yield(File(it)) }
            }
            yield(File(System.getProperty("user.home"), "Android/Sdk"))
            yield(File("/usr/local/lib/android/sdk"))
            yield(File("/opt/android-sdk"))
        }
    for (sdkRoot in candidates) {
        val jar =
            File(sdkRoot, "platforms")
                .listFiles()
                ?.filter { it.isDirectory }
                ?.maxByOrNull { it.name.removePrefix("android-").toIntOrNull() ?: 0 }
                ?.resolve("android.jar")
        if (jar != null && jar.exists()) {
            return jar
        }
    }
    error(
        "android.jar not found — set ANDROID_HOME or create local.properties with sdk.dir= " +
            "(needed by :providers:* to dex plugin packages)",
    )
}
