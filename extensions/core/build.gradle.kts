plugins {
    id("cloudimage.android.library")
    id("cloudimage.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.cloudimage.extensions.core"

    // The engine's tests install a real extension package through the URL
    // classloader seam; the payload classes come from :fixture:demo-provider.
    sourceSets {
        getByName("test") {
            resources.srcDir(layout.buildDirectory.dir("generated/test-fixtures"))
        }
    }
}

// Brings in exactly the demo fixture jar (not provider:api's, which the host
// supplies at runtime) and re-exposes it as a test resource.
val demoProvider: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

val copyDemoProviderFixture by tasks.registering(Copy::class) {
    from(demoProvider)
    into(layout.buildDirectory.dir("generated/test-fixtures"))
    rename { "demo-provider.jar" }
}

tasks.matching { it.name.lowercase().contains("test") }.configureEach {
    dependsOn(copyDemoProviderFixture)
}

dependencies {
    api(project(":provider:api"))
    implementation(project(":core:network"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    demoProvider(project(mapOf("path" to ":fixture:demo-provider", "configuration" to "fixtureJar")))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
