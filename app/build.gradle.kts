plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "it.techreloaded.hybridbuddy.app"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "it.techreloaded.hybridbuddy"
        minSdk = 31
        targetSdk = 36
        versionCode = 3
        versionName = "0.2.0"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
}
