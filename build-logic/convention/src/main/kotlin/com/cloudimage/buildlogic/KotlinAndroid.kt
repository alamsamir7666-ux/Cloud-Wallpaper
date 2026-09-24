package com.cloudimage.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/**
 * Shared Kotlin configuration used by the convention plugins.
 * Keeps Java/Kotlin compatibility at 17 across every module.
 *
 * Uses the `compilerOptions` DSL (Kotlin 2.2+); the old `kotlinOptions`
 * block was removed in Kotlin 2.4.
 */
internal fun Project.configureKotlin() {
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}

internal val Project.libs
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")
