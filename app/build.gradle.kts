plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    compileSdk = 35  // compilation toujours sur SDK 35

    defaultConfig {
        applicationId = "com.parnasse.miroir.v4"
        minSdk = 29
        targetSdk = 29  // Onyx SDK nécessite targetSdk<=29 pour les hidden API
        versionCode = 1
        versionName = "0.4.0"

        ndk {
            abiFilters += listOf("arm64-v8a")  // Boox uniquement
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        buildConfig = true
    }

    packaging {
        jniLibs {
            pickFirsts.add("**/*.so")
        }
    }

    aaptOptions {
        noCompress("tflite")  // TFLite requiert des fichiers non compressés
    }

    namespace = "com.parnasse.miroir"  // namespace Kotlin inchangé (R, BuildConfig)
    // applicationId = "com.parnasse.miroir.v4" → installable en parallèle du stable
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Onyx SDK — doit être implémentée (pas dans le framework)
    implementation("com.onyx.android.sdk:onyxsdk-pen:1.5.1") {
        exclude(group = "com.android.support")
    }
    implementation("com.onyx.android.sdk:onyxsdk-device:1.3.2") {
        exclude(group = "com.android.support")
    }
    implementation("com.onyx.android.sdk:onyxsdk-base:1.8.3") {
        exclude(group = "com.android.support")
    }

    // Hidden API bypass — nécessaire au runtime pour Onyx SDK
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")

    // RxJava — requis par TouchHelper
    implementation("io.reactivex.rxjava2:rxjava:2.2.21")
    implementation("io.reactivex.rxjava2:rxandroid:2.1.1")

    // Google ML Kit Digital Ink — reconnaissance écriture manuscrite
    implementation("com.google.mlkit:digital-ink-recognition:18.1.0")

    // Tests unitaires JVM (Règle d'Or — régression du séquenceur, sans tablette)
    testImplementation("junit:junit:4.13.2")
}
