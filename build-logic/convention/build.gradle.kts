plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
    compileOnly(libs.ktlint.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
    compileOnly(libs.hilt.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "cloudimage.android.application"
            implementationClass = "com.cloudimage.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "cloudimage.android.library"
            implementationClass = "com.cloudimage.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "cloudimage.android.compose"
            implementationClass = "com.cloudimage.buildlogic.AndroidComposeConventionPlugin"
        }
        register("hilt") {
            id = "cloudimage.hilt"
            implementationClass = "com.cloudimage.buildlogic.HiltConventionPlugin"
        }
    }
}
