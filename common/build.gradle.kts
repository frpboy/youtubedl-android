plugins {
    `maven-publish`
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}
android {
    publishing { singleVariant("release") { withSourcesJar() } }
    namespace = "com.yausername.youtubedl_common"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
}
dependencies {
    implementation(libs.androidx.core.ktx)
    implementation("commons-io:commons-io:2.5")
    implementation("org.apache.commons:commons-compress:1.12")
}

val apiDocumentationJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
    from(rootProject.file("docs"))
    from(rootProject.file("LICENSE"))
    from(rootProject.file("NOTICE"))
}
publishing {
    publications {
        register<MavenPublication>("release") {
            afterEvaluate { from(components["release"]) }
            artifactId = project.name
            artifact(apiDocumentationJar)
            pom {
                name.set("frpboy youtubedl-android " + project.name)
                description.set("Seali-compatible Android wrapper. Runtime payloads are supplied separately by the app; this artifact is not a standalone downloader.")
                url.set("https://github.com/frpboy/youtubedl-android")
                licenses { license { name.set("GNU General Public License v3.0"); url.set("https://www.gnu.org/licenses/gpl-3.0.html") } }
                developers { developer { id.set("frpboy"); name.set("Rahul Muraleedharan") }; developer { id.set("yausername"); name.set("yausername (upstream)") } }
                scm { url.set("https://github.com/frpboy/youtubedl-android"); connection.set("scm:git:https://github.com/frpboy/youtubedl-android.git") }
            }
        }
    }
    repositories { maven { name = "staging"; url = uri(rootProject.layout.buildDirectory.dir("staging-deploy")) } }
}
