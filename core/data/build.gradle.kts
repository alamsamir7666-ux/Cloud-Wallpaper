plugins {
    id("cloudimage.android.library")
    id("cloudimage.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.cloudimage.core.data"

    testOptions {
        unitTests {
            // Robolectric decodes real bitmaps in the wallpaper action tests.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    api(project(":core:model"))
    // The sources facade surfaces NetworkResult, so consuming modules need
    // the network types on their compile classpath.
    api(project(":core:network"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":extensions:core"))
    implementation(project(":provider:api"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.test.ext.junit)
}
