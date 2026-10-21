import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Firebase settings come from local.properties or CI env, never from a committed google-services.json.
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
    namespace = "app.mawja.admin"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.mawja.admin"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${cfg("firebase.projectId", "MAWJA_FIREBASE_PROJECT_ID", "mawjaFirebaseProjectId")}\"")
        buildConfigField("String", "FIREBASE_APP_ID", "\"${cfg("firebase.adminAppId", "MAWJA_FIREBASE_ADMIN_APP_ID", "mawjaFirebaseAdminAppId")}\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"${cfg("firebase.apiKey", "MAWJA_FIREBASE_API_KEY", "mawjaFirebaseApiKey")}\"")
        buildConfigField("String", "FUNCTIONS_REGION", "\"europe-west1\"")
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
            cfg("firebase.adminDebugAppId", "MAWJA_FIREBASE_ADMIN_DEBUG_APP_ID", "mawjaFirebaseAdminDebugAppId")
                .takeIf { it.isNotBlank() }
                ?.let { buildConfigField("String", "FIREBASE_APP_ID", "\"$it\"") }
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    sourceSets {
        // Membership-code generator shared with the app (docs/codes.md).
        getByName("main").java.directories.add("$rootDir/shared/codes/kotlin")
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation(platform("com.google.firebase:firebase-bom:33.16.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-functions")
    implementation("com.google.firebase:firebase-firestore")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0")
}
