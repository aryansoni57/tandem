// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    id("tandem.android.application")
    id("tandem.compose")
    id("tandem.hilt")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.androidx.baselineprofile)
}

baselineProfile {
    // Generated on a device with `:app:generateBaselineProfile`, then committed under app/src/main.
    automaticGenerationDuringBuild = false
}

dependencies {
    baselineProfile(project(":baselineprofile"))
    implementation(project(":core"))
    implementation(project(":plugins"))
    implementation(project(":designsystem"))
    implementation(project(":protocol"))

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.accessibility)
    implementation(libs.androidx.profileinstaller)
    // The media service lives in :plugins; lint needs its base class on the app classpath too.
    implementation(libs.androidx.media3.session)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
