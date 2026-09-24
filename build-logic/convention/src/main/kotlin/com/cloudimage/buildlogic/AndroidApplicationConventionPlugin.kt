package com.cloudimage.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Convention for the :app application module.
 * Applies AGP, Kotlin and ktlint, then pins SDK levels and Java 17.
 *
 * Kotlin compilation comes from AGP 9's built-in Kotlin support — the
 * org.jetbrains.kotlin.android plugin is no longer applied (it is an
 * error to combine it with AGP 9). The Kotlin version is raised to the
 * catalog's kotlin version through the buildscript classpath override
 * in the root build file.
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("com.android.application")
                apply("org.jlleitschuh.gradle.ktlint")
            }

            extensions.configure<ApplicationExtension> {
                compileSdk = 37

                defaultConfig {
                    minSdk = 26
                    targetSdk = 37
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
