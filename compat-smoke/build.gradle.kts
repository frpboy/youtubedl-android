plugins { id("com.android.application"); alias(libs.plugins.kotlin.android) }
android {
 namespace = "com.frpboy.mavencompat"
 compileSdk = 36
 defaultConfig { applicationId = "com.frpboy.mavencompat"; minSdk = 26; targetSdk = 36; versionCode = 1; versionName = "qualification-only" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
 kotlinOptions { jvmTarget = "11" }
}
dependencies {
 implementation(project(":common")); implementation(project(":library")); implementation(project(":ffmpeg"))
 implementation("commons-io:commons-io:2.22.0")
 implementation("org.apache.commons:commons-compress:1.28.0")
 implementation("com.fasterxml.jackson.core:jackson-databind:2.21.8")
}
