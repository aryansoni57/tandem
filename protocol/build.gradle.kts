// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    id("tandem.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    api(libs.kotlinx.serialization.json)
}
