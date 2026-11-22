// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")
            pluginManager.apply("org.jetbrains.kotlin.android")
            extensions.configure<ApplicationExtension> {
                configureAndroidCommon(this)
                namespace = TandemBuild.NAMESPACE_ROOT
                defaultConfig {
                    applicationId = TandemBuild.NAMESPACE_ROOT
                    targetSdk = TandemBuild.TARGET_SDK
                    versionCode = 1
                    versionName = "0.1.0"
                }
                packaging {
                    resources {
                        // Apache MINA SSHD and its dependencies each ship these. They are build notes, not code.
                        excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST")
                    }
                }
                buildTypes {
                    release {
                        isMinifyEnabled = true
                        isShrinkResources = true
                        proguardFiles(
                            getDefaultProguardFile("proguard-android-optimize.txt"),
                            "proguard-rules.pro",
                        )
                    }
                }
            }
        }
    }
}
