plugins {
    id("cloudimage.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.cloudimage.core.model"
}

dependencies {
    // @Serializable on domain models: navigation passes the selected
    // wallpaper between screens as an encoded JSON argument.
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
