plugins {
    id("cloudimage.android.library")
    id("cloudimage.hilt")
}

android {
    namespace = "com.cloudimage.core.data"
}

dependencies {
    api(project(":core:model"))
    // The Wallhaven repository interface surfaces NetworkResult, so consuming
    // modules need the network types on their compile classpath.
    api(project(":core:network"))
    implementation(project(":core:database"))
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
}
