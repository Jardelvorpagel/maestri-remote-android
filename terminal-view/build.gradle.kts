plugins {
    id("com.android.library") version "9.1.1"
}

// Vendored from https://github.com/termux/termux-app @ 8629e632fcb95da272221be327db653fb24befe9
// (master 2026-09-26). Only the `terminal-view` library dir is vendored; src/test is excluded.
// See VENDOR-README.md.
android {
    namespace = "com.termux.view"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":terminal-emulator"))
    implementation("androidx.annotation:annotation:1.9.0")
}
