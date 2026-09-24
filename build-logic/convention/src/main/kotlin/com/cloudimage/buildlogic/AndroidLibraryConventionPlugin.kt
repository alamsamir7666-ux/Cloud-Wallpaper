package com.cloudimage.buildlogic

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Convention for library modules (:core:*, :feature:*, :provider:api).
 * Applies AGP library, Kotlin and ktlint, then pins SDK levels and Java 17.
 *
 * Kotlin compilation comes from AGP 9's built-in Kotlin support — the
 * org.jetbrains.kotlin.android plugin is no longer applied (it is an
 * error to combine it with AGP 9).
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("com.android.library")
                apply("org.jlleitschuh.gradle.ktlint")
            }

            extensions.configure<LibraryExtension> {
                compileSdk = 37

                defaultConfig {
                    minSdk = 26
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                }

                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
            }

            configureKotlin()
        }
    }
}
