plugins {
    id("cloudimage.android.library")
    id("cloudimage.android.compose")
    id("cloudimage.hilt")
}

android {
    namespace = "com.cloudimage.feature.browser"

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:network"))

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.hilt.lifecycle.viewmodel.compose)

    testImplementation(project(":core:testing"))
    testImplementation(libs.kotlinx.coroutines.test)
}
