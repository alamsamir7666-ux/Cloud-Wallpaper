plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":provider:api"))
}

// Exposes the compiled fixture classes as a standalone jar to the engine's
// tests, which package it into an extension zip and load it through the
// URL classloader seam. Publishing a custom configuration (instead of the
// default runtimeElements) keeps provider:api's jar out of the payload —
// the host supplies the contract at runtime, exactly like production.
val fixtureJar =
    configurations.create("fixtureJar") {
        isCanBeConsumed = true
        isCanBeResolved = false
        outgoing.artifact(tasks.named("jar"))
    }
