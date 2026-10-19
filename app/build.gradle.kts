plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yusuf.ytklip"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.yusuf.ytklip"
        // Kütüphanenin minSdk'si 24. 29 seçtim: MediaStore ile İndirilenler'e
        // yazmak için izin kodu gerekmiyor.
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        ndk {
            // Çoğu modern telefon arm64-v8a. Eski 32 bit telefonlar için armeabi-v7a.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
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

    packaging {
        // yt-dlp/python/ffmpeg native dosyaları çıkarılmış olarak paketlenmeli.
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
}
