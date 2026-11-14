# Android wrapper API and runtime contract

Modules: common, library, ffmpeg. Minimum Android API 26. JVM bytecode target 11; producer uses JDK 17, SDK 36, AGP 8.10.1 and Kotlin 2.0.20.

The compatibility packages remain com.yausername.youtubedl_android, com.yausername.youtubedl_common and com.yausername.ffmpeg. Maven ownership is independent of Java package names.

Call YoutubeDL.init(context) and FFmpeg.init(context) after supplying the app's separately qualified native payload. Build a YoutubeDLRequest, add options, then call YoutubeDL.execute(request, processId, callback). Blocking calls belong on a worker thread. YoutubeDL.destroyProcessById cancels the owned invocation. Duplicate active process identifiers are rejected. Each invocation has a private temporary directory and cleans it only after its process exits.

The AARs intentionally contain no native libraries. The app must package its exact, source-qualified Python launcher/archive, FFmpeg/FFprobe/archive and QuickJS executable. Current Seali also owns the engine/EJS/runtime capability and input-hash checks outside these modules. Do not substitute inherited upstream JNI libraries or assume that compiling this wrapper qualifies native execution. Aria2 is not supplied or connected.

library includes the V6 baseline yt-dlp raw resource unchanged. Its SHA256 and every source file are recorded in the baseline source bindings. Reviewed integrity/restart-recovery source from Seali6e6c6e1a is included. The checker asset hash is verified in the AAR. Stable/Nightly consent is app-owned; this wrapper does not authorize it. This wrapper release is not an approved native/runtime release; the consuming app qualifies its own native payloads.

The supplied sources JAR contains the actual Kotlin source used to build each AAR. This documentation JAR contains this API guide rather than generated Kotlin HTML. Upstream GPL-3.0 license and attribution are preserved.

## Bundled third-party code and licenses

The wrapper source is GPL-3.0 (see LICENSE), derived from yausername/youtubedl-android and the junkfood02 fork. The library AAR also bundles the yt-dlp engine (res/raw/ytdlp, yt-dlp 2026.10.26, sha256 e51e69e89489f199530f13d32a4342da3ddb2cf34842f364603a066db05afbf3), which contains the yt_dlp and yt_dlp_ejs packages. yt-dlp and yt-dlp-ejs are distributed by their authors under the Unlicense (public domain dedication); see https://github.com/yt-dlp/yt-dlp/blob/2026.10.26/LICENSE and https://github.com/yt-dlp/ejs/blob/main/LICENSE. The zip itself carries no separate license file, so these URLs are the source of record. Native Python, FFmpeg and QuickJS binaries are not included.
