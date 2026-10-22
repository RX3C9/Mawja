// :shared-remote — remote config (config/{app|downloader}) and announcements read token-free from
// Firestore REST: parsing, cache codec, gates, feature switches, tier gates and announcement
// visibility (docs: work/remote-config-design.md). Pure logic is JVM-tested from :app.
plugins {
    id("com.android.library")
}

android {
    namespace = "app.mawja.remote"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
}
