plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bioracle.pure"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bioracle.pure"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.camera:camera-camera2:1.5.0-alpha05")
    implementation("androidx.camera:camera-lifecycle:1.5.0-alpha05")
    implementation("androidx.camera:camera-view:1.5.0-alpha05")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
}
