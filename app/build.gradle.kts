plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

dependencies {
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

android {
    namespace = "app.conversate.glass"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "app.conversate.glass"
        minSdk = 29
        // The glasses run Android 12 (API 32). Targeting the device's own level
        // keeps runtime behavior identical to what we measure on it.
        targetSdk = 32
        versionCode = 7
        versionName = "0.3.2-power"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = false
        // Personal sideloaded device app, intentionally Android 12 runtime semantics.
        disable += "ExpiredTargetSdkVersion"
    }
}
