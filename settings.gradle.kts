// SPDX-License-Identifier: GPL-3.0-or-later
pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "tandem"

include(":protocol")
include(":transport")
include(":sftp")
include(":core")
include(":plugins")
include(":designsystem")
include(":app")
include(":baselineprofile")
include(":testing:mockpeer")
