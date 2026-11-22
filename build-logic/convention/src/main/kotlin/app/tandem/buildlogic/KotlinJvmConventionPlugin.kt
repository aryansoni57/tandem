// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.buildlogic

import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType

/** Pure JVM modules (protocol, transport, testing). No Android dependencies allowed. */
class KotlinJvmConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            // Pin the Java target too: otherwise compileJava follows the host JDK (25) and
            // Kotlin's jvmTarget (17) disagrees with it.
            extensions.configure<JavaPluginExtension> {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            configureKotlinJvmTarget()
            dependencies {
                add("implementation", libsCatalog.findLibrary("kotlinx-coroutines-core").get())
                add("testImplementation", libsCatalog.findLibrary("kotlin-test-junit5").get())
                add("testImplementation", libsCatalog.findLibrary("junit-jupiter").get())
                add("testRuntimeOnly", libsCatalog.findLibrary("junit-platform-launcher").get())
            }
            tasks.withType<Test>().configureEach {
                useJUnitPlatform()
            }
        }
    }
}
