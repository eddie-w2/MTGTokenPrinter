plugins {
    id("com.android.application") version "9.4.0" apply false
    // AGP 9 compiles Kotlin itself ("built-in Kotlin"); only the Compose compiler plugin is declared here.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}
