plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ancsbridge.watch"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ancsbridge.watch"
        minSdk = 33 // Wear OS 4 (Galaxy Watch 6 Classic base SDK)
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        // URL del backend (ajustable desde la UI o en compilación)
        buildConfigField("String", "WS_BACKEND_URL", "\"wss://tu-backend.up.railway.app/ws?role=watch\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
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
    // Wear OS Compose
    implementation("androidx.wear.compose:compose-material:1.3.1")
    implementation("androidx.wear.compose:compose-foundation:1.3.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-service:2.8.2")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // OkHttp (Cliente WebSocket resiliente y ligero)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
