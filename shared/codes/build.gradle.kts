// :shared-codes — membership-code format and redeem policy (docs/codes.md), used by :app and
// :admin. Plain Kotlin on java.* only; a library module so both apps compile the same classes
// instead of adding an extra source directory to each (which AGP's built-in Kotlin ignored).
plugins {
    id("com.android.library")
}

android {
    namespace = "app.mawja.codes"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
