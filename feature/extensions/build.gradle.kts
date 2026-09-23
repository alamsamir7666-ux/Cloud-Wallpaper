plugins {
    id("cloudimage.android.library")
    id("cloudimage.android.compose")
    id("cloudimage.hilt")
}

android {
    namespace = "com.cloudimage.feature.extensions"

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":extensions:core"))
    implementation(project(":core:data"))
    implementation(project(":core:datastore"))

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.hilt.navigation.compose)

    testImplementation(project(":core:testing"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
