plugins {
    id("com.android.application") version "9.3.2" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}
// Optional local build output keeps OneDrive from locking Android intermediates.
val pocketBuildDir = providers.gradleProperty("pocketBuildDir").orNull
if (pocketBuildDir != null) allprojects { layout.buildDirectory.set(file("$pocketBuildDir/${project.name}")) }
