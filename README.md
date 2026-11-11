# FrpBoy Android yt-dlp wrappers

Source-built Maven wrappers for Seali, based on yausername/youtubedl-android and the junkfood02 fork. The compatibility Java/Kotlin packages remain unchanged; Maven coordinates identify this fork without erasing upstream authorship.

## Status

Private staging only. Nothing from this branch has been published to Maven Central. The current source is bound to Seali's owned-wrapper baseline at commit 9f25e318c46f0777d087c021e71d242e417c8d09. The pending integrity updater and Stable/Nightly policy changes must be reviewed and rebound before public release. This staging branch is not an approved runtime release.

The old inherited native binaries, sample app and disconnected aria2 module are not included in this producer. These AARs do not contain a standalone downloader runtime. Seali must continue to supply its separately qualified native payloads and engine/QuickJS/EJS checks. Do not replace them with the old fork's opaque JNI binaries.

## Build locally

Use JDK17 and Android SDK36. Set ANDROID_HOME or an untracked local.properties sdk.dir.

```sh
./gradlew :common:publishReleasePublicationToStagingRepository \
  :library:publishReleasePublicationToStagingRepository \
  :ffmpeg:publishReleasePublicationToStagingRepository \
  :library:testReleaseUnitTest
python3 scripts/verify-publication.py --repository build/staging-deploy \
  --group io.github.frpboy.youtubedl-android \
  --version 0.1.0-alpha.7-staging.1 \
  --engine-sha256 "$(cat engine-sha256.txt)"
```

This writes only to build/staging-deploy. No credentials, remote repository or automatic release is configured. Publication group/version can be overridden using mavenGroup/mavenVersion Gradle properties. The verified Central namespace is io.github.frpboy. Planned module names are common, library and ffmpeg under io.github.frpboy.youtubedl-android; the staging suffix is not a released version.

Each module generates an AAR, exact Kotlin sources JAR, documentation JAR, POM and Gradle metadata. Public release additionally requires signatures, source/security review, verified native/runtime compatibility and final consumer custody checks. CI only builds/tests/stages. It cannot publish.

## API and licenses

See [the API and runtime contract](docs/README.md), [NOTICE](NOTICE), and [GPL-3.0 license](LICENSE). Historical upstream build notes are preserved under docs/upstream for attribution and context, not as proof of current native-source qualification.

The baseline Java dependencies are deliberately unchanged for compatibility. Their age is not an endorsement: review commons-io2.5, commons-compress1.12 and Jackson2.11.1 before public release. Current Jackson branches require Android26 while this project preserves Android24, so do not silently increase the minimum SDK or claim an untested upgrade safe.
