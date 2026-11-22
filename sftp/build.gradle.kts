// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    id("tandem.kotlin.jvm")
}

dependencies {
    // Apache MINA SSHD (Apache-2.0, compatible with GPL-3.0). Runs on the JVM and on Android 8 and later.
    api(libs.sshd.core)
    api(libs.sshd.sftp)
    implementation(libs.slf4j.api)
}
