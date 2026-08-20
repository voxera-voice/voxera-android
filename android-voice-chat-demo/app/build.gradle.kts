plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace  = "com.rocs.demo"
    compileSdk = 35

    defaultConfig {
        applicationId  = "com.rocs.demo"
        minSdk         = 26
        targetSdk      = 35
        versionCode    = 1
        versionName    = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }
}

dependencies {
    // ROCS Voice SDK (local) — uses stripped mediasoup-client (no bundled WebRTC)
    implementation(project(":sdk-android"))

    // Comera Jitsi Meet SDK — package 1223
    // https://gitlab.avrioc.io/comera/jitsi-sdk/conference-sdk-android-releases/-/packages/1223
    // TODO: uncomment and replace TODO_VERSION with the actual version
    // implementation("org.jitsi.react:jitsi-meet-sdk:TODO_VERSION") {
    //     exclude(group = "io.github.webrtc-sdk", module = "android")
    //     exclude(group = "org.webrtc", module = "google-webrtc")
    // }

    // Jetpack Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
