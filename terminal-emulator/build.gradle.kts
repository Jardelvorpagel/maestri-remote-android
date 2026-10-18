plugins {
    id("com.android.library") version "9.1.1"
}

// Vendored from https://github.com/termux/termux-app @ 8629e632fcb95da272221be327db653fb24befe9
// (master 2026-09-26). Only the `terminal-emulator` library dir is vendored; src/test is
// excluded (union gate has no junit on the classpath). The jni/ dir ships pristine but is
// NOT built (no ndkBuild block): Maestri has no local PTY, so JNI.createSubprocess is never
// reached and libtermux.so is never loaded. See VENDOR-README.md.
android {
    namespace = "com.termux.emulator"
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
    implementation("androidx.annotation:annotation:1.9.1")
}
