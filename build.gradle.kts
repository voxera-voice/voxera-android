plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
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
    // Public, unprefixed WebRTC distribution. `api` is required because the
    // Voxera listener and video APIs expose org.webrtc types to applications.
    api("io.github.webrtc-sdk:android:144.7559.09")

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
    repositories {
        maven {
            name = "GitLab"
            url = uri("https://gitlab-eu.avrioc.io/api/v4/projects/${findProperty("gitlabProjectId") ?: System.getenv("GITLAB_PROJECT_ID")}/packages/maven")
            credentials(HttpHeaderCredentials::class) {
                name = if (System.getenv("CI_JOB_TOKEN") != null) "Job-Token" else "Private-Token"
                value = System.getenv("CI_JOB_TOKEN") ?: findProperty("gitlabToken") as String? ?: System.getenv("GITLAB_TOKEN") ?: ""
            }
            authentication {
                create<HttpHeaderAuthentication>("header")
            }
        }
    }
    publications {
        create<MavenPublication>("release") {
            groupId = "com.voxera"
            artifactId = "voxera-sdk-android"
            version = "1.1.42"
            afterEvaluate {
                from(components["release"])
            }
        }
    }
}
