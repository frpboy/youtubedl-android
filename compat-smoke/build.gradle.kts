import java.security.MessageDigest
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

tasks.register("hashRuntimeClasspath") {
    val outFile = File(rootDir, "build/runtime-evidence/runtime-classpath-sha256.txt")
    doLast {
        outFile.parentFile.mkdirs()
        val cfg = configurations.getByName("debugRuntimeClasspath")
        fun view(t: String) = cfg.incoming.artifactView {
            attributes.attribute(Attribute.of("artifactType", String::class.java), t)
        }.files.files
        val files = (view("jar") + view("android-aar")).toSet()
            .filter { f -> "modules-2" in f.absolutePath.replace('\\', '/') }
            .sortedBy { it.name }
        val lines = files.map { f ->
            val digest = MessageDigest.getInstance("SHA-256").digest(f.readBytes())
            digest.joinToString("") { x -> "%02x".format(x) } + "  " + f.name
        }
        outFile.writeText(lines.joinToString("\n") + "\n")
    }
}
