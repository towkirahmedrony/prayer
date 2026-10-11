// Salah — root build script.
//
// Important: Android Gradle Plugin 9.x has built-in Kotlin support, so the app module
// must NOT apply `org.jetbrains.kotlin.android`. The Kotlin version used by the Android
// build is selected by putting the Kotlin Gradle Plugin on the root buildscript classpath
// below. See docs/TECH_STACK.md for the full compatibility reasoning.
buildscript {
    dependencies {
        // Must stay in sync with `kotlin` in gradle/libs.versions.toml.
        // A literal is required here because version-catalog accessors are not available
        // inside the `buildscript {}` block.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
}
