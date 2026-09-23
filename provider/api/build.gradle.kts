plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Plugins parse their providers' JSON; the classes are resolved from the
    // host classloader at runtime (the app ships the same version), so this
    // is an `api` dependency purely to share the compile-time surface.
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
