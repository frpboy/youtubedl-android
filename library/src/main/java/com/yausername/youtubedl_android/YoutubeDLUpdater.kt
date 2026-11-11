package com.yausername.youtubedl_android

import android.content.Context
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.yausername.youtubedl_android.YoutubeDL.UpdateChannel
import com.yausername.youtubedl_android.YoutubeDL.UpdateStatus
import com.yausername.youtubedl_android.YoutubeDL.getInstance
import com.yausername.youtubedl_common.SharedPrefsHelper
import com.yausername.youtubedl_common.SharedPrefsHelper.update
import org.apache.commons.io.FileUtils
import java.io.File
import java.io.IOException
import java.net.URL

internal object YoutubeDLUpdater {
    private const val youtubeDLStableChannelUrl =
        "https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest"
    private const val youtubeDLNightlyChannelUrl =
        "https://api.github.com/repos/yt-dlp/yt-dlp-nightly-builds/releases/latest"
    private const val youtubeDLMasterChannelUrl =
        "https://api.github.com/repos/yt-dlp/yt-dlp-master-builds/releases/latest"
    private const val dlpBinaryName = "yt-dlp"
    private const val dlpVersionKey = "dlpVersion"
    private const val dlpVersionNameKey = "dlpVersionName"

    @Synchronized
    @Throws(IOException::class, YoutubeDLException::class)
    internal fun update(
        appContext: Context?,
        youtubeDLChannel: UpdateChannel = UpdateChannel.STABLE
    ): UpdateStatus {
        val json = checkForUpdate(appContext!!, youtubeDLChannel)
            ?: return UpdateStatus.ALREADY_UP_TO_DATE
        val asset = getDownloadAsset(json)
        val digest = asset["digest"]?.asText().orEmpty()
        check(digest.matches(Regex("sha256:[a-f0-9]{64}"))) { "Release asset has no SHA-256 digest" }
        val binary = File(getYoutubeDLDir(appContext), dlpBinaryName)
        VerifiedEngineReplacement.replace(
            installed = binary,
            expectedSha256 = digest.removePrefix("sha256:"),
            download = { staged ->
                FileUtils.copyURLToFile(URL(asset["browser_download_url"].asText()), staged, 5000, 10000)
            },
            validate = { candidate -> getInstance().verifyRuntimeEngine(appContext, candidate, getTag(json)) },
        )
        updateSharedPrefs(appContext, getTag(json), getName(json))
        return UpdateStatus.DONE
    }

    internal fun updateSharedPrefs(appContext: Context, tag: String, name: String) {
        update(appContext, dlpVersionKey, tag)
        update(appContext, dlpVersionNameKey, name)
    }

    @Throws(IOException::class)
    private fun checkForUpdate(appContext: Context, youtubeDLChannel: UpdateChannel): JsonNode? {
        val url = URL(youtubeDLChannel.apiUrl)
        val json = YoutubeDL.objectMapper.readTree(url)
        val newVersion = getTag(json)
        val oldVersion = SharedPrefsHelper[appContext, dlpVersionKey]
        return if (newVersion == oldVersion) {
            null
        } else json
    }

    private fun getTag(json: JsonNode): String {
        return json["tag_name"].asText()
    }

    private fun getName(json: JsonNode): String {
        return json["name"].asText()
    }

    @Throws(YoutubeDLException::class)
    private fun getDownloadAsset(json: JsonNode): JsonNode {
        val assets = json["assets"] as ArrayNode
        return assets.firstOrNull { it["name"].asText() == YoutubeDL.ytdlpBin }
            ?: throw YoutubeDLException("unable to get engine release asset")
    }

    private fun getYoutubeDLDir(appContext: Context): File {
        val baseDir = File(appContext.noBackupFilesDir, YoutubeDL.baseName)
        return File(baseDir, YoutubeDL.ytdlpDirName)
    }

    fun version(appContext: Context?): String? {
        return SharedPrefsHelper[appContext!!, dlpVersionKey]
    }

    fun versionName(appContext: Context?): String? {
        return SharedPrefsHelper[appContext!!, dlpVersionNameKey]
    }
}