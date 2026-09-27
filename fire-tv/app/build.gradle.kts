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
        ndk { abiFilters += "armeabi-v7a" }
        versionCode = 4
        versionName = "0.4-local-text-probe"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies { implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0") }
