plugins {
    `maven-publish`
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}
android {
    publishing { singleVariant("release") { withSourcesJar() } }
    namespace = "com.yausername.youtubedl_android"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
}
dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(project(":common"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.8")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.21")
    implementation("commons-io:commons-io:2.22.0")
}

dependencies { testImplementation(libs.junit4) }

val apiDocumentationJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
    from(rootProject.file("docs"))
    from(rootProject.file("LICENSE"))
    from(rootProject.file("NOTICE"))
    from(rootProject.file("THIRD_PARTY_NOTICES.md"))
}
publishing {
    publications {
        register<MavenPublication>("release") {
            afterEvaluate { from(components["release"]) }
            artifactId = project.name
            artifact(apiDocumentationJar)
            pom {
                name.set("frpboy youtubedl-android " + project.name)
                description.set("Seali-compatible Android wrapper for yt-dlp. The AAR bundles the yt-dlp engine (Unlicense) as a raw resource; native Python, FFmpeg and QuickJS payloads are supplied separately by the app. Not a standalone downloader.")
                url.set("https://github.com/frpboy/youtubedl-android")
                licenses { license { name.set("GNU General Public License v3.0"); url.set("https://www.gnu.org/licenses/gpl-3.0.html") } }
                developers { developer { id.set("frpboy"); name.set("frpboy") }; developer { id.set("yausername"); name.set("yausername (upstream)") } }
                scm { url.set("https://github.com/frpboy/youtubedl-android"); connection.set("scm:git:https://github.com/frpboy/youtubedl-android.git") }
            }
        }
    }
    repositories { maven { name = "staging"; url = uri(rootProject.layout.buildDirectory.dir("staging-deploy")) } }
}
