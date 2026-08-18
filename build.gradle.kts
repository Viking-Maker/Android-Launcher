// Root project. Plugin versions are centralized in gradle/libs.versions.toml
// so the build is identical from Android Studio and `./gradlew` (wrapper is source of truth).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
