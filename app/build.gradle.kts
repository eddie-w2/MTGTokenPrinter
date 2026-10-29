plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Signing key settings. They come from ~/.gradle/gradle.properties on your computer, or from environment
// variables on GitHub. None of the values live in this repo. Without them, builds use the debug key.
fun signingSetting(name: String): String? =
    providers.gradleProperty(name).orElse(providers.environmentVariable(name)).orNull?.takeIf { it.isNotBlank() }

val sStorePath = signingSetting("TOKENPRINTER_STORE_FILE")
val sStorePass = signingSetting("TOKENPRINTER_STORE_PASSWORD")
val sKeyAlias = signingSetting("TOKENPRINTER_KEY_ALIAS")
val sKeyPass = signingSetting("TOKENPRINTER_KEY_PASSWORD")
val haveKey = sStorePath != null && sStorePass != null && sKeyAlias != null && sKeyPass != null &&
    java.io.File(sStorePath).exists()

android {
    namespace = "com.example.tokenprinter"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.tokenprinter"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "0.6"
    }
    signingConfigs {
        if (haveKey) {
            create("tokenprinter") {
                storeFile = java.io.File(sStorePath!!)
                storePassword = sStorePass
                keyAlias = sKeyAlias
                keyPassword = sKeyPass
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            // Your own key when its settings are present, otherwise the debug key so the build still installs.
            signingConfig = signingConfigs.getByName(if (haveKey) "tokenprinter" else "debug")
        }
        debug {
            // Same key for debug builds, so Studio and GitHub builds update each other.
            if (haveKey) signingConfig = signingConfigs.getByName("tokenprinter")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
