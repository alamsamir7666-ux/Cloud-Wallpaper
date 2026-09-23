plugins {
    id("cloudimage.android.library")
    id("cloudimage.android.compose")
}

android {
    namespace = "com.cloudimage.feature.extensions"
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core:model"))

    implementation(libs.androidx.navigation.compose)
}
