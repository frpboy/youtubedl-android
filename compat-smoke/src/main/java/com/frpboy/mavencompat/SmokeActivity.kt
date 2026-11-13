package com.frpboy.mavencompat
import android.app.Activity
import android.os.Bundle
import android.os.Build
import android.widget.TextView
import android.widget.ScrollView
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
  val result=JSONObject().put("sdk",Build.VERSION.SDK_INT).put("minSdkContract",26).put("complete",false)
  val checks=JSONObject();val errors=JSONObject();result.put("checks",checks).put("errors",errors)
  val base=File(filesDir,"qualification");base.mkdirs()
  fun persist() {
   val temporary=File(filesDir,"result.json.tmp");temporary.writeText(result.toString(2))
   check(temporary.renameTo(File(filesDir,"result.json"))) { "atomic receipt replacement failed" }
  }
  fun probe(name:String, work:()->Unit) {
   val start=android.os.SystemClock.elapsedRealtime()
   try { work();checks.put(name,true) }
   catch(t:Throwable) { checks.put(name,false);errors.put(name,JSONObject().put("class",t.javaClass.name).put("stack",t.stackTraceToString())) }
   result.put("lastProbe",name).put("lastProbeMs",android.os.SystemClock.elapsedRealtime()-start)
   persist()
  }
  probe("FileUtils.copyInputStreamToFile") {
   val copy=File(base,"copied.txt");FileUtils.copyInputStreamToFile(ByteArrayInputStream("copy-pass".toByteArray()),copy)
   check(copy.readText()=="copy-pass")
  }
  probe("FileUtils.copyFile") {
   val source=File(base,"file-copy-source.txt");source.writeText("copy-pass")
   val dest=File(base,"second.txt");FileUtils.copyFile(source,dest);check(dest.readText()=="copy-pass")
  }
  probe("IOUtils.toString") {
   check(IOUtils.toString(ByteArrayInputStream("unicode-π".toByteArray()),StandardCharsets.UTF_8)=="unicode-π")
  }
  probe("ZipUtils.unzipAndVerify.symlink") {
   val zip=File(base,"fixture.zip");assets.open("fixture.zip").use { input -> zip.outputStream().use { input.copyTo(it) } }
   val extraction=File(base,"extracted-"+android.os.SystemClock.elapsedRealtimeNanos());extraction.mkdirs();ZipUtils.unzipAndVerify(zip,extraction)
   check(File(extraction,"usr/lib/content.txt").readText()=="zip-pass")
   check(File(extraction,"usr/bin/content-link").readText()=="zip-pass")
  }
  probe("ZipUtils.stream") {
   val extraction=File(base,"stream");extraction.mkdirs()
   assets.open("stream.zip").use { ZipUtils.unzip(it,extraction) }
   check(File(extraction,"nested/value.txt").readText()=="stream-pass")
  }
  probe("Jackson.VideoInfo") {
   val mapper=ObjectMapper();val video=mapper.readValue("{\"id\":\"qualified-id\",\"title\":\"Unicode π\",\"unknown_field\":true}",VideoInfo::class.java)
   check(mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(video).get("id").asText()=="qualified-id")
  }
  probe("Jackson.releaseTree") {
   val mapper=ObjectMapper();val release=mapper.readTree("{\"tag_name\":\"2026.11.12\",\"assets\":[{\"name\":\"yt-dlp\",\"browser_download_url\":\"https://example.invalid/fixture-only\"}]}")
   check(release["tag_name"].asText()=="2026.11.12" && release["assets"].size()==1)
  }
  probe("Jackson.depthReject") {
   val mapper=ObjectMapper();var rejected=false
   try { mapper.readTree("[".repeat(20000)+"0"+"]".repeat(20000)) }
   catch(expected:com.fasterxml.jackson.core.exc.StreamConstraintsException) { rejected=true }
   check(rejected)
  }
  probe("Jackson.updateReadTree") {
   val mapper=ObjectMapper()
   val original=mapper.readTree("{\"id\":\"orig\",\"title\":\"old\",\"view_count\":1}")
   val update=mapper.readTree("{\"title\":\"new\"}")
   val merged:com.fasterxml.jackson.databind.JsonNode=mapper.readerForUpdating(original).readValue(update.traverse())
   check(merged===original && merged["id"].asText()=="orig" && merged["title"].asText()=="new" && merged["view_count"].asInt()==1)
  }
  probe("FileUtils.deleteDirectory") {
   val delete=File(base,"delete/sub");delete.mkdirs();File(delete,"file").writeText("cleanup")
   FileUtils.deleteDirectory(File(base,"delete"));check(!File(base,"delete").exists())
  }
  probe("FileUtils.deleteQuietly") {
   val copy=File(base,"quiet-delete.txt");copy.writeText("cleanup")
   FileUtils.deleteQuietly(copy);check(!copy.exists())
  }
  val names=checks.keys().asSequence().toList()
  result.put("pass",names.size==11 && names.all { checks.getBoolean(it) }).put("complete",true)
  persist()
  setContentView(ScrollView(this).apply { addView(TextView(this@SmokeActivity).apply { text=result.toString(2);setPadding(24,24,24,24) }) })
 }
}
