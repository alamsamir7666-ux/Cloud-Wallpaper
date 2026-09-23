plugins {
    id("cloudimage.android.application")
    id("cloudimage.android.compose")
    id("cloudimage.hilt")
}

android {
    namespace = "com.cloudimage.app"

    defaultConfig {
        applicationId = "com.cloudimage.app"
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:network"))
    implementation(project(":core:data"))
    implementation(project(":extensions:core"))
    implementation(project(":feature:browse"))
    implementation(project(":feature:detail"))
    implementation(project(":feature:extensions"))
    implementation(project(":provider:api"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    testImplementation(libs.junit)
}
