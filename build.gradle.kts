// SPDX-License-Identifier: GPL-3.0-or-later
// Declared here so the Android, Kotlin, KSP and Hilt plugins share one classloader with
// detekt, which inspects Android extensions when it is applied to an Android module.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    // The host JDK is 25, which detekt does not accept as a target. Pin the same target as Kotlin.
    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        jvmTarget = "17"
    }

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        config.setFrom(rootProject.file("detekt.yml"))
        buildUponDefaultConfig = true
        parallel = true
    }
}

tasks.register("check-all") {
    group = "verification"
    description = "Runs ktlint, detekt, lint and unit tests for every module."
    dependsOn(subprojects.map { "${it.path}:check" })
}
