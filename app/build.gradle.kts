plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "com.addonserver"
    compileSdk = 30

    defaultConfig {
        applicationId = "com.addonserver"
        minSdk = 21
        targetSdk = 30
        versionCode = 1
        versionName = "1.0.0"

        // Force 32-bit only for Skyworth mt5867 (armeabi-v7a)
        ndk {
            abiFilters += listOf("armeabi-v7a", "armeabi")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
    }
}

chaquopy {
    defaultConfig {
        // Python 3.8 for broad Android compatibility
        version = "3.8"

        pip {
            // No external pip packages - use stdlib only for minimal footprint
        }
    }

    sourceSets {
        getByName("main") {
            setSrcDirs(listOf("src/main/python"))
        }
    }
}

dependencies {
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")

    // TV Leanback UI (lighter than Compose for TV, better for 1.5GB RAM)
    implementation("androidx.leanback:leanback:1.0.0")

    // Lifecycle & ViewModel
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // OkHttp for Telegram API calls (lightweight, no retrofit needed)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Gson for JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")

    // Material components (for dialogs if needed)
    implementation("com.google.android.material:material:1.9.0")

    // ConstraintLayout for TV status UI
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
