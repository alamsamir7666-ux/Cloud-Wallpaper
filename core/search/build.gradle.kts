plugins {
    id("cloudimage.android.library")
    id("cloudimage.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.cloudimage.core.search"
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:network"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

// TEMPORARY DIAGNOSTIC (remove after the CI-only merged-engine test
// failures are root-caused): full stack traces and test stdout in the
// console, so the CI log carries what the one-line summary hides.
tasks.withType<Test> {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
}
