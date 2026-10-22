plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ancsbridge.watch"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ancsbridge.watch"
        minSdk = 33
        targetSdk = 34
        versionCode = 2
        versionName = "2.0.0"

        buildConfigField("String", "WS_BACKEND_URL", "\"ws://192.168.100.136:8080/ws?role=watch\"")
        buildConfigField("String", "HTTP_BACKEND_URL", "\"http://192.168.100.136:8080\"")
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
    implementation("androidx.wear.compose:compose-material:1.3.1")
    implementation("androidx.wear.compose:compose-foundation:1.3.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-service:2.8.2")

    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.health.connect:connect-client:1.1.0-alpha07")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
