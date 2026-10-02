plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

// Must match the version the React Native and Flutter bridges pin, and the
// tag pushed to voxera-voice/voxera-android. Three places, one value.
val voxeraVersion = "1.1.43"

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
    publications {
        create<MavenPublication>("release") {
            // Distributed through JitPack, which builds this repository at a
            // tag and serves it without an account or token — GitHub Packages,
            // used before, required a token even to download a public package.
            // These coordinates are the ones JitPack serves for this repo.
            groupId = "com.github.voxera-voice"
            artifactId = "voxera-android"
            version = voxeraVersion
            afterEvaluate {
                from(components["release"])
            }

            // Without this the generated POM carries only coordinates and
            // dependencies. Gradle resolves that fine, but every human-facing
            // surface — the GitHub Packages listing, dependency reports,
            // licence scanners in a customer's build — shows an unnamed,
            // unlicensed artifact.
            pom {
                name.set("Voxera Android SDK")
                description.set(
                    "Native Android client for the Voxera realtime voice and video platform."
                )
                url.set("https://voxera-voice.com")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://github.com/voxera-voice/voxera-android/blob/main/LICENSE")
                        distribution.set("repo")
                    }
                }

                developers {
                    developer {
                        id.set("voxera")
                        name.set("Voxera")
                        email.set("voicevoxera@gmail.com")
                    }
                }

                scm {
                    url.set("https://github.com/voxera-voice/voxera-android")
                    connection.set("scm:git:https://github.com/voxera-voice/voxera-android.git")
                    developerConnection.set("scm:git:ssh://git@github.com/voxera-voice/voxera-android.git")
                }
            }
        }
    }
}
