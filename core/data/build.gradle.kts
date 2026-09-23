plugins {
    id("cloudimage.android.library")
    id("cloudimage.hilt")
}

android {
    namespace = "com.cloudimage.core.data"
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:database"))
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
