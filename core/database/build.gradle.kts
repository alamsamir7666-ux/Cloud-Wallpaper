plugins {
    id("cloudimage.android.library")
    id("cloudimage.hilt")
}

android {
    namespace = "com.cloudimage.core.database"

    testOptions {
        unitTests {
            // Robolectric needs to read merged resources in DAO tests.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    api(libs.room.runtime)
    api(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.test.ext.junit)
}
