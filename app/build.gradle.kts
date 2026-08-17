plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.qcc.leaudiorecord"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.qcc.leaudiorecord"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // 纯 framework 实现，无额外 androidx 依赖
    // Vosk 离线语音识别引擎
    implementation("com.alphacephei:vosk-android:0.3.47")
}
