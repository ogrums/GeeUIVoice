plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.geeui.voice"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.geeui.voice"
        minSdk = 29
        targetSdk = 29
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        aidl = true
    }
}

dependencies {
    implementation(project(":voice-core"))
    implementation(project(":emotion-core"))
}
