// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    id("tandem.kotlin.jvm")
}

dependencies {
    api(project(":protocol"))
    testImplementation(project(":testing:mockpeer"))
}
