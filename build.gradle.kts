plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android") version "1.9.22"
    id("maven-publish")
}

android {
    namespace = "com.voxera.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        targetSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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
    // WebRTC
    implementation("io.github.webrtc-sdk:android:121.0.0")

    // Socket.IO
    implementation("io.socket:socket.io-client:2.1.0") {
        exclude(group = "org.json", module = "json")
    }

    // JSON
    implementation("org.json:json:20231013")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Android core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}

publishing {
    publications {
        create<MavenPublication>("release") {
            groupId = "com.mayavoice"
            artifactId = "sdk-android"
            version = "1.0.0"
            afterEvaluate {
                from(components["release"])
            }
        }
    }
}
