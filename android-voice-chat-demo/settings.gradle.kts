pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Comera – Jitsi Meet SDK (conference-sdk-android-releases)
        maven {
            url = uri("https://gitlab.avrioc.io/api/v4/projects/1223/packages/maven")
            credentials(HttpHeaderCredentials::class) {
                name = "Private-Token"
                value = providers.gradleProperty("gitlabToken").orNull
                    ?: System.getenv("GITLAB_TOKEN") ?: ""
            }
            authentication {
                create<HttpHeaderAuthentication>("header")
            }
        }
    }
}

rootProject.name = "RocsChatDemo"
include(":app")
include(":sdk-android")
project(":sdk-android").projectDir = file("..")
