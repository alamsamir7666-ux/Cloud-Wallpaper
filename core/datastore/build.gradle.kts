plugins {
    id("cloudimage.android.library")
    id("cloudimage.hilt")
}

android {
    namespace = "com.cloudimage.core.datastore"
}

dependencies {
    api(libs.androidx.datastore.preferences)
    implementation(project(":core:model"))
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
