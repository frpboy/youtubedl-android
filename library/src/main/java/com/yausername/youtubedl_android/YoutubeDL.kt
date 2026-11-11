package com.yausername.youtubedl_android

import android.content.Context
import android.os.Build
import com.fasterxml.jackson.databind.ObjectMapper
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.mapper.VideoInfo
import com.yausername.youtubedl_common.SharedPrefsHelper
import com.yausername.youtubedl_common.SharedPrefsHelper.update
import com.yausername.youtubedl_common.utils.ZipUtils.unzip
import org.apache.commons.io.FileUtils
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.Collections
import kotlin.collections.set

object YoutubeDL {
    private var initialized = false
    private var pythonPath: File? = null
    private var ffmpegPath: File? = null
    private var ytdlpPath: File? = null
    private var binDir: File? = null
    private var ENV_LD_LIBRARY_PATH: String? = null
    private var ENV_SSL_CERT_FILE: String? = null
    private var ENV_PYTHONHOME: String? = null
    private var TMPDIR: String = ""
    private val idProcessMap = HashMap<String, OwnedInvocation>()

    @Synchronized
    @Throws(YoutubeDLException::class)
    fun init(appContext: Context) {
        if (initialized) return
        val baseDir = File(appContext.noBackupFilesDir, baseName)
        if (!baseDir.exists()) baseDir.mkdir()
        val packagesDir = File(baseDir, packagesRoot)
        binDir = File(appContext.applicationInfo.nativeLibraryDir)
        pythonPath = File(binDir, pythonBinName)
        ffmpegPath = File(binDir, ffmpegBinName)
        val pythonDir = File(packagesDir, pythonDirName)
        val ffmpegDir = File(packagesDir, ffmpegDirName)
        val aria2cDir = File(packagesDir, aria2cDirName)
        val ytdlpDir = File(baseDir, ytdlpDirName)
        ytdlpPath = File(ytdlpDir, ytdlpBin)
        ENV_LD_LIBRARY_PATH = pythonDir.absolutePath + "/usr/lib" + ":" +
                ffmpegDir.absolutePath + "/usr/lib" + ":" +
                aria2cDir.absolutePath + "/usr/lib"
        ENV_SSL_CERT_FILE = pythonDir.absolutePath + "/usr/etc/tls/cert.pem"
        ENV_PYTHONHOME = pythonDir.absolutePath + "/usr"
        TMPDIR = appContext.cacheDir.absolutePath
        initPython(appContext, pythonDir)
        init_ytdlp(appContext, ytdlpDir)
        initialized = true
    }

    @Throws(YoutubeDLException::class)
    fun init_ytdlp(appContext: Context, ytdlpDir: File) {
        if (!ytdlpDir.exists()) ytdlpDir.mkdirs()
        val ytdlpBinary = File(ytdlpDir, ytdlpBin)
        VerifiedEngineReplacement.requireNoIncompleteUpdate(ytdlpBinary)
        if (!ytdlpBinary.exists()) {
            try {
                val inputStream =
                    appContext.resources.openRawResource(R.raw.ytdlp) /* will be renamed to yt-dlp */
                FileUtils.copyInputStreamToFile(inputStream, ytdlpBinary)
            } catch (e: Exception) {
                FileUtils.deleteQuietly(ytdlpDir)
                throw YoutubeDLException("failed to initialize", e)
            }
        }
    }

    /** Bind preferences only after the app has verified its installed bundled engine bytes. */
    fun recordVerifiedEngineVersion(appContext: Context, version: String) {
        YoutubeDLUpdater.updateSharedPrefs(appContext, version, version)
    }

    fun recoverInterruptedEngine(appContext: Context, assetPath: String, pinnedSha256: String): Boolean {
        val installed = File(appContext.noBackupFilesDir, "$baseName/$ytdlpDirName/$ytdlpBin")
        return VerifiedEngineReplacement.recover(installed, pinnedSha256, pinnedAsset = { stage ->
            appContext.assets.open(assetPath).use { input -> stage.outputStream().use { input.copyTo(it) } }
        }) != null
    }

    fun verifyRecoveredEngine(appContext: Context, assetPath: String, pinnedSha256: String, pinnedVersion: String): String {
        val installed = checkNotNull(ytdlpPath)
        return try {
            verifyRuntimeEngine(appContext, installed, "*")
        } catch (_: Exception) {
            // A byte-valid retained engine may still be incompatible. Restore the owned pinned asset.
            VerifiedEngineReplacement.replace(installed, pinnedSha256, download = { stage ->
                appContext.assets.open(assetPath).use { input -> stage.outputStream().use { input.copyTo(it) } }
            }, validate = { candidate -> verifyRuntimeEngine(appContext, candidate, pinnedVersion) })
            pinnedVersion
        }
    }

    internal fun verifyRuntimeEngine(appContext: Context, candidate: File, expectedVersion: String): String {
        val work = File.createTempFile("seali-engine-check-", ".py", appContext.cacheDir)
        val log = File.createTempFile("seali-engine-check-", ".log", appContext.cacheDir)
        try {
            appContext.assets.open("seali-engine/verify-runtime-engine.py").use { input ->
                work.outputStream().use { input.copyTo(it) }
            }
            val builder = ProcessBuilder(
                checkNotNull(pythonPath).absolutePath, work.absolutePath, candidate.absolutePath,
                File(checkNotNull(binDir), "libqjs.so").absolutePath, expectedVersion,
            ).redirectErrorStream(true).redirectOutput(log)
            builder.environment().apply {
                this["LD_LIBRARY_PATH"] = ENV_LD_LIBRARY_PATH
                this["SSL_CERT_FILE"] = ENV_SSL_CERT_FILE
                this["PYTHONHOME"] = ENV_PYTHONHOME
                this["HOME"] = ENV_PYTHONHOME
                this["TMPDIR"] = appContext.cacheDir.absolutePath
                selectOwnedPythonConfig(checkNotNull(ENV_PYTHONHOME))
            }
            val process = builder.start()
            try {
                val deadline = System.nanoTime() + 20_000_000_000L
                var status: Int? = null
                while (status == null && System.nanoTime() < deadline) {
                    status = try { process.exitValue() } catch (_: IllegalThreadStateException) { null }
                    if (status == null) Thread.sleep(50)
                }
                check(status == 0) { "Engine/EJS offline capability check failed or timed out" }
                return objectMapper.readTree(log.readText().trim().lineSequence().last())["engine"].asText()
            } finally { process.destroy() }
        } finally { work.delete(); log.delete() }
    }

    @Throws(YoutubeDLException::class)
    fun initPython(appContext: Context, pythonDir: File) {
        val pythonLib = File(binDir, pythonLibName)
        try {
            VerifiedPythonPayload.install(pythonLib, pythonDir, extractAndVerify = { archive, staged ->
                com.yausername.youtubedl_common.utils.ZipUtils.unzipAndVerify(archive, staged)
            })
            update(appContext, pythonLibVersion, "sha256:" + VerifiedEngineReplacement.digest(pythonLib))
        } catch (e: Exception) {
            // Retain both the previous directory and failed staging for inspection.
            throw YoutubeDLException("failed to initialize verified Python payload", e)
        }
    }

    private fun assertInit() {
        check(initialized) { "instance not initialized" }
        VerifiedEngineReplacement.requireNoIncompleteUpdate(checkNotNull(ytdlpPath))
    }

    @Throws(YoutubeDLException::class, InterruptedException::class, CanceledException::class)
    fun getInfo(url: String): VideoInfo {
        val request = YoutubeDLRequest(url)
        return getInfo(request)
    }

    @Throws(YoutubeDLException::class, InterruptedException::class, CanceledException::class)
    fun getInfo(request: YoutubeDLRequest): VideoInfo {
        request.addOption("--dump-json")
        val response = execute(request, null, null)
        val videoInfo: VideoInfo = try {
            objectMapper.readValue(response.out, VideoInfo::class.java)
        } catch (e: IOException) {
            throw YoutubeDLException("Unable to parse video information", e)
        } ?: throw YoutubeDLException("Failed to fetch video information")
        return videoInfo
    }

    private fun ignoreErrors(request: YoutubeDLRequest, out: String): Boolean {
        return request.hasOption("--dump-json") && !out.isEmpty() && request.hasOption("--ignore-errors")
    }

    fun destroyProcessById(id: String): Boolean = synchronized(idProcessMap) {
        idProcessMap[id]?.cancel() ?: false
    }

    class CanceledException : Exception()

    @JvmOverloads
    @Throws(YoutubeDLException::class, InterruptedException::class, CanceledException::class)
    fun execute(
        request: YoutubeDLRequest,
        processId: String? = null,
        callback: ((Float, Long, String) -> Unit)? = null
    ): YoutubeDLResponse {
        assertInit()
        val invocation = try { OwnedInvocation.create(File(TMPDIR)) }
        catch (e: IOException) { throw YoutubeDLException("Private temporary directory unavailable", e) }
        if (processId != null) synchronized(idProcessMap) {
            if (idProcessMap.containsKey(processId)) {
                invocation.cleanAfterExit()
                throw YoutubeDLException("Process ID already exists")
            }
            idProcessMap[processId] = invocation
        }
        try {
        // disable caching unless explicitly requested
        if (!request.hasOption("--cache-dir") || request.getOption("--cache-dir") == null) {
            request.addOption("--no-cache-dir")
        }

        if (request.buildCommand().contains("libaria2c.so")) {
            request
                .addOption("--external-downloader-args", "aria2c:--summary-interval=1")
                .addOption(
                    "--external-downloader-args",
                    "aria2c:--ca-certificate=$ENV_SSL_CERT_FILE"
                )
        }

        /* Set ffmpeg location, See https://github.com/xibr/ytdlp-lazy/issues/1 */
        request.addOption("--ffmpeg-location", ffmpegPath!!.absolutePath)
        val youtubeDLResponse: YoutubeDLResponse
        val process: Process
        val exitCode: Int
        val outBuffer = StringBuffer() //stdout
        val errBuffer = StringBuffer() //stderr
        val startTime = System.currentTimeMillis()
        val args = request.buildCommand()
        val command: MutableList<String?> = ArrayList()
        command.addAll(listOf(pythonPath!!.absolutePath, ytdlpPath!!.absolutePath))
        command.addAll(args)
        val processBuilder = ProcessBuilder(command)
        processBuilder.environment().apply {
            this["LD_LIBRARY_PATH"] = ENV_LD_LIBRARY_PATH
            this["SSL_CERT_FILE"] = ENV_SSL_CERT_FILE
            this["PATH"] = System.getenv("PATH") + ":" + binDir!!.absolutePath
            this["PYTHONHOME"] = ENV_PYTHONHOME
            this["HOME"] = ENV_PYTHONHOME
            selectOwnedPythonConfig(checkNotNull(ENV_PYTHONHOME))
            this["TMPDIR"] = invocation.directory.absolutePath
        }

        process = try {
            processBuilder.start()
        } catch (e: IOException) {
            throw YoutubeDLException(e)
        }
        invocation.attach(process)
        val outStream = process.inputStream
        val errStream = process.errorStream
        val stdOutProcessor = StreamProcessExtractor(outBuffer, outStream, callback)
        val stdErrProcessor = StreamGobbler(errBuffer, errStream)
        exitCode = try {
            stdOutProcessor.join()
            stdErrProcessor.join()
            process.waitFor()
        } catch (e: InterruptedException) {
            invocation.cancel()
            invocation.stopAndWait()
            throw e
        }
        val out = outBuffer.toString()
        val err = errBuffer.toString()
        if (invocation.wasCancelled()) throw CanceledException()
        if (exitCode > 0) {
            if (!ignoreErrors(request, out)) {
                throw YoutubeDLException(err)
            }
        }

        val elapsedTime = System.currentTimeMillis() - startTime
        youtubeDLResponse = YoutubeDLResponse(command, exitCode, elapsedTime, out, err)
        return youtubeDLResponse
        } finally {
            invocation.stopAndWait()
            // Remove only this reservation; never another concurrent replacement.
            if (processId != null) synchronized(idProcessMap) {
                if (idProcessMap[processId] === invocation) idProcessMap.remove(processId)
            }
            invocation.cleanAfterExit()
        }
    }

    @Synchronized
    @Throws(YoutubeDLException::class)
    fun updateYoutubeDL(
        appContext: Context,
        updateChannel: UpdateChannel = UpdateChannel.STABLE
    ): UpdateStatus? {
        assertInit()
        return try {
            YoutubeDLUpdater.update(appContext, updateChannel)
        } catch (e: IOException) {
            throw YoutubeDLException("failed to update youtube-dl", e)
        }
    }

    fun version(appContext: Context?): String? {
        return YoutubeDLUpdater.version(appContext)
    }

    fun versionName(appContext: Context?): String? {
        return YoutubeDLUpdater.versionName(appContext)
    }

    enum class UpdateStatus {
        DONE, ALREADY_UP_TO_DATE
    }

    open class UpdateChannel(val apiUrl: String) {
        object STABLE : UpdateChannel("https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest")
        object NIGHTLY :
            UpdateChannel("https://api.github.com/repos/yt-dlp/yt-dlp-nightly-builds/releases/latest")
        object MASTER :
            UpdateChannel("https://api.github.com/repos/yt-dlp/yt-dlp-master-builds/releases/latest")

        companion object {
            @JvmField
            val _STABLE: STABLE = STABLE

            @JvmField
            val _NIGHTLY: NIGHTLY = NIGHTLY

            @JvmField
            val _MASTER: MASTER = MASTER
        }
    }


    const val baseName = "youtubedl-android"
    private const val packagesRoot = "packages"
    private const val pythonBinName = "libpython.so"
    private const val pythonLibName = "libpython.zip.so"
    private const val pythonDirName = "python"
    private const val ffmpegDirName = "ffmpeg"
    private const val ffmpegBinName = "libffmpeg.so"
    private const val aria2cDirName = "aria2c"
    const val ytdlpDirName = "yt-dlp"
    const val ytdlpBin = "yt-dlp"
    private const val pythonLibVersion = "pythonLibVersion"
    val objectMapper = ObjectMapper()

    @JvmStatic
    fun getInstance() = this
}