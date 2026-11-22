// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    id("tandem.android.library")
    id("tandem.hilt")
}

dependencies {
    api(project(":protocol"))
    api(project(":transport"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.bouncycastle.bcpkix)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockk)
    testImplementation(project(":testing:mockpeer"))
}
