plugins {
    id("cloudimage.android.library")
    id("cloudimage.hilt")
}

android {
    namespace = "com.cloudimage.core.muzei"
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:datastore"))

    // The Muzei source contract: MuzeiArtProvider, Artwork, ProviderClient.
    implementation(libs.muzei.api)

    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":core:testing"))
    testImplementation(libs.androidx.datastore.preferences)
}
