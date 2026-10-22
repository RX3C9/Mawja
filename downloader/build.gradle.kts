import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Firebase project for the token-free remote config read (config/downloader). Same lookup as :admin;
// no Firebase SDK and no google-services.json here.
val local =
    Properties().apply {
        rootDir.resolve("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }

fun cfg(
    key: String,
    env: String,
    property: String,
): String = local.getProperty(key) ?: System.getenv(env) ?: (project.findProperty(property) as String?) ?: ""

android {
    namespace = "app.mawja.downloader"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.mawja.downloader"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        manifestPlaceholders["downloadPermission"] = "app.mawja.permission.DOWNLOAD"
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${cfg("firebase.projectId", "MAWJA_FIREBASE_PROJECT_ID", "mawjaFirebaseProjectId")}\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"${cfg("firebase.apiKey", "MAWJA_FIREBASE_API_KEY", "mawjaFirebaseApiKey")}\"")
    }

    signingConfigs {
        // Shared, committed debug key: stable SHA-1 for Google sign-in and same signer for the
        // app, the Downloader and Admin (signature permission). Debug builds only; never release.
        getByName("debug") {
            storeFile = rootDir.resolve("keystore/mawja-debug.p12")
            storeType = "pkcs12"
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["downloadPermission"] = "app.mawja.permission.DOWNLOAD_DEBUG"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":downloader-api"))
    implementation(project(":shared-remote"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
}
