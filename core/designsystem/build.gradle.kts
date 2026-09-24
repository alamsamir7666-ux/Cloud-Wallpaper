plugins {
    id("cloudimage.android.library")
    id("cloudimage.android.compose")
}

android {
    namespace = "com.cloudimage.core.designsystem"
}

dependencies {
    api(project(":core:model"))

    implementation(libs.coil.compose)
}
