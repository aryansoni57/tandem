// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

class ComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
            // The registered extension type is the concrete AGP type, so look it up by name.
            val android = extensions.getByName("android") as CommonExtension<*, *, *, *, *, *>
            android.buildFeatures.compose = true
            val bom = libsCatalog.findLibrary("androidx-compose-bom").get()
            dependencies {
                add("implementation", platform(bom))
                add("implementation", libsCatalog.findLibrary("androidx-compose-ui").get())
                add("implementation", libsCatalog.findLibrary("androidx-compose-ui-tooling-preview").get())
                add("implementation", libsCatalog.findLibrary("androidx-compose-material3").get())
                add("implementation", libsCatalog.findLibrary("androidx-activity-compose").get())
            }
        }
    }
}
