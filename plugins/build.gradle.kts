// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    id("tandem.android.library")
    id("tandem.hilt")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":protocol"))
    implementation(project(":sftp"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.common)

    testImplementation(libs.junit4)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.android)
}
