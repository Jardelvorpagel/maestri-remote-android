// Maestri Remote Android — root build file.
// AGP 9.x ships built-in Kotlin support: no org.jetbrains.kotlin.android plugin here.
// The Compose compiler and serialization compiler plugins are applied per module.
plugins {
    id("com.android.application") version "9.1.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}