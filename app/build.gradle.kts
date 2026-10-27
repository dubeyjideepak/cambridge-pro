plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cambridge.streamer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cambridge.streamer"
        minSdk = 26
        targetSdk = 34
        versionCode = 35
        versionName = "1.4.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // CameraX (Supports External UVC Capture Cards out-of-the-box on Pixel/Samsung)
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // Universal Native USB UVC Driver (for OnePlus, Xiaomi, Vivo, Oppo, etc.)
    implementation("com.herohan:UVCAndroid:1.0.13")

    // Lightweight embedded HTTP server for live laptop browser preview
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    // NanoWSD WebSocket extension — powers zero-freeze WebRTC signaling server
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")

    // Google WebRTC for Android — SRTP/UDP zero-freeze live camera bridging
    // Replaces HTTP-FLV (TCP) with WebRTC (UDP) — eliminates all TCP head-of-line blocking freezes
    implementation("io.github.webrtc-sdk:android:125.6422.07")

    // Embedded QR Code Generation (for dynamic UPI QR codes)
    implementation("com.google.zxing:core:3.5.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")

    // RootEncoder SRT (Secure Reliable Transport for OBS Studio with ARQ zero-freeze)
    implementation("com.github.pedroSG94.RootEncoder:library:2.4.4")
    implementation("com.github.pedroSG94.RootEncoder:srt:2.4.4")
}
