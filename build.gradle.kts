// Root build file — all module configuration lives in
// build-logic/convention plugins, versions in gradle/libs.versions.toml.
//
// AGP 9 compiles Kotlin through its built-in Kotlin support (the
// org.jetbrains.kotlin.android plugin is no longer applied per module and
// AGP's floor is KGP 2.2.10). These classpath pins raise the built-in
// Kotlin and KSP to the catalog's versions — keep in sync with the
// `kotlin` and `ksp` entries in gradle/libs.versions.toml.
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
        classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.3.12")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ktlint) apply false
}
