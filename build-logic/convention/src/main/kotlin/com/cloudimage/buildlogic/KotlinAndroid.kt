package com.cloudimage.buildlogic

import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/**
 * Shared Kotlin configuration used by the convention plugins.
 * Keeps Java/Kotlin compatibility at 17 across every module.
 */
internal fun Project.configureKotlin() {
    tasks.withType<KotlinCompile>().configureEach {
        kotlinOptions {
            jvmTarget = JavaVersion.VERSION_17.toString()
        }
    }
}

internal val Project.libs
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")
