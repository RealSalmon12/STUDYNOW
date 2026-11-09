plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.focuslock.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.focuslock.app"
        minSdk = 28
        targetSdk = 34  // 34: 안드로이드 15의 강제 전체화면(edge-to-edge) 레이아웃을 피하기 위해
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 개인 설치용: 디버그 키로 서명 (따로 키를 만들 필요 없음)
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
