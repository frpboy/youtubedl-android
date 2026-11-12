package com.frpboy.mavencompat
import android.app.Activity
import android.os.Bundle
import android.os.Build
import android.widget.TextView
import com.fasterxml.jackson.databind.ObjectMapper
import com.yausername.youtubedl_android.mapper.VideoInfo
import com.yausername.youtubedl_common.utils.ZipUtils
import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
class SmokeActivity : Activity() {
 override fun onCreate(state: Bundle?) {
  super.onCreate(state)
  val result=JSONObject().put("sdk",Build.VERSION.SDK_INT).put("minSdkContract",24)
  val checks=JSONObject();result.put("checks",checks)
  try {
   val base=File(filesDir,"qualification");base.mkdirs()
   val copy=File(base,"copied.txt")
   FileUtils.copyInputStreamToFile(ByteArrayInputStream("copy-pass".toByteArray()),copy)
   check(copy.readText()=="copy-pass");checks.put("FileUtils.copyInputStreamToFile",true)
   val second=File(base,"second.txt");FileUtils.copyFile(copy,second);check(second.readText()=="copy-pass");checks.put("FileUtils.copyFile",true)
   check(IOUtils.toString(ByteArrayInputStream("unicode-π".toByteArray()),StandardCharsets.UTF_8)=="unicode-π");checks.put("IOUtils.toString",true)
   val zip=File(base,"fixture.zip");assets.open("fixture.zip").use { input -> zip.outputStream().use { input.copyTo(it) } }
   val extraction=File(base,"extracted");extraction.mkdirs();ZipUtils.unzipAndVerify(zip,extraction)
   check(File(extraction,"usr/lib/content.txt").readText()=="zip-pass")
   check(File(extraction,"usr/bin/content-link").readText()=="zip-pass");checks.put("ZipUtils.unzipAndVerify.symlink",true)
   val streamExtraction=File(base,"stream");streamExtraction.mkdirs()
   assets.open("stream.zip").use { ZipUtils.unzip(it,streamExtraction) }
   check(File(streamExtraction,"nested/value.txt").readText()=="stream-pass");checks.put("ZipUtils.stream",true)
   val mapper=ObjectMapper();val video=mapper.readValue("{\"id\":\"qualified-id\",\"title\":\"Unicode π\",\"unknown_field\":true}",VideoInfo::class.java)
   check(mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(video).get("id").asText()=="qualified-id");checks.put("Jackson.VideoInfo",true)
   val release=mapper.readTree("{\"tag_name\":\"2026.11.12\",\"assets\":[{\"name\":\"yt-dlp\",\"browser_download_url\":\"https://example.invalid/fixture-only\"}]}")
   check(release["tag_name"].asText()=="2026.11.12" && release["assets"].size()==1);checks.put("Jackson.releaseTree",true)
   var rejected=false
   try { mapper.readTree("[".repeat(20000)+"0"+"]".repeat(20000)) }
   catch (expected:com.fasterxml.jackson.core.exc.StreamConstraintsException) { rejected=true }
   check(rejected);checks.put("Jackson.depthReject",true)
   val delete=File(base,"delete/sub");delete.mkdirs();File(delete,"file").writeText("cleanup")
   FileUtils.deleteDirectory(File(base,"delete"));check(!File(base,"delete").exists());checks.put("FileUtils.deleteDirectory",true)
   FileUtils.deleteQuietly(copy);check(!copy.exists());checks.put("FileUtils.deleteQuietly",true)
   result.put("pass",true)
  } catch (t:Throwable) { result.put("pass",false).put("error",t.stackTraceToString()) }
  File(filesDir,"result.json").writeText(result.toString(2))
  setContentView(TextView(this).apply { text=result.toString(2);setPadding(24,24,24,24) })
 }
}
