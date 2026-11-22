// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

class HiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.google.devtools.ksp")
            pluginManager.apply("com.google.dagger.hilt.android")
            dependencies {
                add("implementation", libsCatalog.findLibrary("hilt-android").get())
                add("ksp", libsCatalog.findLibrary("hilt-compiler").get())
            }
        }
    }
}
