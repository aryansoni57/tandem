// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    `kotlin-dsl`
}

group = "app.tandem.buildlogic"

dependencies {
    implementation(libs.android.gradlePlugin)
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.ksp.gradlePlugin)
    implementation(libs.hilt.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "tandem.android.application"
            implementationClass = "app.tandem.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "tandem.android.library"
            implementationClass = "app.tandem.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("kotlinJvm") {
            id = "tandem.kotlin.jvm"
            implementationClass = "app.tandem.buildlogic.KotlinJvmConventionPlugin"
        }
        register("compose") {
            id = "tandem.compose"
            implementationClass = "app.tandem.buildlogic.ComposeConventionPlugin"
        }
        register("hilt") {
            id = "tandem.hilt"
            implementationClass = "app.tandem.buildlogic.HiltConventionPlugin"
        }
    }
}
