plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "tv.slopoff"
    compileSdk = 35
    buildFeatures { buildConfig = true }
    // Sideload-only API 28 Fire OS experiment; Google Play publication is out of scope.
    lint { disable += "ExpiredTargetSdkVersion" }
    defaultConfig {
        applicationId = "tv.slopoff"
        minSdk = 28
        targetSdk = 28
        versionCode = 3
        versionName = "0.3-armed-probe"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
