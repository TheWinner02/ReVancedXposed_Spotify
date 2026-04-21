pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        flatDir {
            dirs("libs")
        }
        google()
        mavenCentral()
        maven(url = "https://api.xposed.info")
        maven(url = "https://oss.sonatype.org/content/repositories/snapshots")
        maven(url = "https://jitpack.io")
    }
}

plugins {
    id("com.android.settings") version ("9.1.1")
}

android {
    compileSdk = 37
    minSdk = 27
}

rootProject.name = "Revanced Xposed FE"
include(":app")
include(":stub")
