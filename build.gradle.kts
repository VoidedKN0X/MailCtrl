// AGP 9 compiles Kotlin itself ("built-in Kotlin"), so the org.jetbrains.kotlin.android plugin is gone.
// AGP only guarantees its own minimum Kotlin Gradle plugin; this pins the version the Compose and
// serialization compiler plugins below belong to.
buildscript {
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
