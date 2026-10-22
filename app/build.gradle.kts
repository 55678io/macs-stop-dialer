plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.macsstop.dialer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.macsstop.dialer"
        minSdk = 26
        targetSdk = 34
        val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = run
        versionName = "2.$run"
    }

    signingConfigs {
        create("macs") {
            storeFile = file("macs-release.keystore")
            storePassword = "macsstop123"
            keyAlias = "macs"
            keyPassword = "macsstop123"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("macs")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("macs")
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
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("org.linphone:linphone-sdk-android:5.4.128")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.11.0")
}
