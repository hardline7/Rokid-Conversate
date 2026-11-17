plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val generatedLens = layout.buildDirectory.dir("generated/lens")
val copyLensSources by tasks.registering(Copy::class) {
    from("../app/src/main/java") {
        include("app/conversate/glass/meeting/MeetingLensView.kt")
        include("app/conversate/glass/meeting/L10n.kt")
    }
    into(generatedLens)
}
android {
    namespace = "app.conversate.glass.lenspreview"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "app.conversate.glass.lenspreview"
        minSdk = 29
        targetSdk = 32
        versionCode = 1
        versionName = "preview"
    }
    sourceSets["main"].java.srcDir(generatedLens)
    sourceSets["main"].assets.srcDir("../app/src/main/assets")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { disable += "ExpiredTargetSdkVersion" }
}
tasks.named("preBuild") { dependsOn(copyLensSources) }
