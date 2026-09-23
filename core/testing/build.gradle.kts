plugins {
    id("cloudimage.android.library")
}

android {
    namespace = "com.cloudimage.core.testing"
}

dependencies {
    api(project(":core:data"))
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.test)
    api(libs.junit)
}
