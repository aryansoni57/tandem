// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    id("tandem.kotlin.jvm")
    application
}

dependencies {
    implementation(project(":protocol"))
    implementation(project(":transport"))
    implementation(libs.bouncycastle.bcpkix)
}

application {
    mainClass.set("app.tandem.testing.mockpeer.MockPeerCliKt")
}
