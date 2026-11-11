# Android wrapper API and runtime contract

Modules: common, library, ffmpeg. Minimum Android API 24. JVM bytecode target 11; producer uses JDK 17, SDK 36, AGP 8.10.1 and Kotlin 2.0.20.

The compatibility packages remain com.yausername.youtubedl_android, com.yausername.youtubedl_common and com.yausername.ffmpeg. Maven ownership is independent of Java package names.

Call YoutubeDL.init(context) and FFmpeg.init(context) after supplying the app's separately qualified native payload. Build a YoutubeDLRequest, add options, then call YoutubeDL.execute(request, processId, callback). Blocking calls belong on a worker thread. YoutubeDL.destroyProcessById cancels the owned invocation. Duplicate active process identifiers are rejected. Each invocation has a private temporary directory and cleans it only after its process exits.

The AARs intentionally contain no native libraries. The app must package its exact, source-qualified Python launcher/archive, FFmpeg/FFprobe/archive and QuickJS executable. Current Seali also owns the engine/EJS/runtime capability and input-hash checks outside these modules. Do not substitute inherited upstream JNI libraries or assume that compiling this wrapper qualifies native execution. Aria2 is not supplied or connected.

library includes the V6 baseline yt-dlp raw resource unchanged. Its SHA256 and every source file are recorded in the baseline source bindings. Source changes from Seali's pending integrity updater and Stable/Nightly policy work must be reviewed and rebound before public release. This baseline staging version is not the approved runtime release.

The supplied sources JAR contains the actual Kotlin source used to build each AAR. This documentation JAR contains this API guide rather than generated Kotlin HTML. Upstream GPL-3.0 license and attribution are preserved.
