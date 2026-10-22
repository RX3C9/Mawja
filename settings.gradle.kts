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
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Mawja"
include(":app")
include(":benchmark")
include(":downloader-api")
include(":downloader")
include(":admin")
include(":shared-codes")
project(":shared-codes").projectDir = file("shared/codes")
include(":shared-remote")
project(":shared-remote").projectDir = file("shared/remote")
