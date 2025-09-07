plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.example.cnnct"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.cnnct"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Custom BuildConfig fields
        buildConfigField("String", "AGORA_APP_ID", "\"3678d2cf11ad47579391de324b308fcd\"")
        buildConfigField("String", "AGORA_TOKEN_URL", "\"https://get-agora-token-840694397310.europe-west1.run.app\"")
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        compose = true
        buildConfig = true // enable BuildConfig generation for your buildConfigField
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.11"
    }

    // If you later need to add packaging or other settings, do it here.
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:5.1.0")
    implementation("com.squareup.moshi:moshi:1.15.2")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.2")
    implementation("io.agora.rtc:full-sdk:4.6.0")
    implementation("com.vanniktech:android-image-cropper:4.6.0")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2025.08.01"))
    implementation("androidx.activity:activity-compose")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    implementation("androidx.compose.material:material-icons-extended")

    // Firebase BoM & artifacts
    implementation(platform("com.google.firebase:firebase-bom:34.2.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-storage")
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.android.gms:play-services-auth:21.4.0")

    // Coroutines, Coil, Media3, etc.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.2")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    implementation("androidx.media3:media3-datasource:1.8.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.8.0")

    // AndroidX core / appcompat / material / lifecycle
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.3")
    implementation("androidx.lifecycle:lifecycle-process:2.9.3")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
