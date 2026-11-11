plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
}
subprojects {
    group = providers.gradleProperty("mavenGroup").getOrElse("io.github.frpboy.youtubedl-android")
    version = providers.gradleProperty("mavenVersion").getOrElse("0.1.0-alpha.7-staging.2")
}
