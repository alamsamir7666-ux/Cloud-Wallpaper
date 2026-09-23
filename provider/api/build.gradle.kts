plugins {
    id("cloudimage.android.library")
}

android {
    namespace = "com.cloudimage.provider.api"
}

dependencies {
    testImplementation(libs.junit)
}
