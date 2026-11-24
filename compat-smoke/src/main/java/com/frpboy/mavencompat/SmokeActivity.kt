package com.frpboy.mavencompat
import android.app.Activity
import android.os.Bundle
import android.os.Build
import android.widget.TextView
import android.widget.ScrollView
import com.fasterxml.jackson.databind.ObjectMapper
import com.yausername.youtubedl_android.EngineChangedException
import com.yausername.youtubedl_android.EngineExpectation
import com.yausername.youtubedl_android.EngineGuard
import com.yausername.youtubedl_android.LaunchCancellation
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import com.yausername.youtubedl_common.utils.ZipUtils
import kotlin.concurrent.thread
import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
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
  // The whole battery runs off the main thread: probes wait on real children for
  // tens of seconds, and an ANR would invalidate the runtime harness.
  thread(start=true,name="qualification-probes") {
   fun probe(name:String, work:()->Unit) {
    val start=android.os.SystemClock.elapsedRealtime()
    try { work();checks.put(name,true) }
    catch(t:Throwable) { checks.put(name,false);errors.put(name,JSONObject().put("class",t.javaClass.name).put("stack",t.stackTraceToString())) }
    result.put("lastProbe",name).put("lastProbeMs",android.os.SystemClock.elapsedRealtime()-start)
    persist()
   }
   fun sha256(bytes:ByteArray):String {
    val digest=MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it.toInt() and 255) }
   }
   fun sha256(file:File):String = sha256(file.readBytes())
   fun fixtureSha():String = resources.openRawResource(R.raw.ytdlp).use { sha256(it.readBytes()) }
   val ytdlpDir=File(noBackupFilesDir,"youtubedl-android/yt-dlp")
   val invocationsParent=File(cacheDir,"seali-owned-invocations")
   val receiptPrefixes=listOf("fixture-child-started-","fixture-child-starting-","fixture-release-","fixture-handshake-error-","fixture-child-error-")
   fun sentinels(dir:File)=dir.listFiles()?.filter { it.isFile && it.name.startsWith("fixture-child-started-") } ?: emptyList()
   fun handshakeErrors(dir:File)=dir.listFiles()?.filter { it.isFile && it.name.startsWith("fixture-handshake-error-") } ?: emptyList()
   fun receipts(dir:File)=dir.listFiles()?.filter { f -> f.isFile && receiptPrefixes.any { f.name.startsWith(it) } } ?: emptyList()
   fun clearReceipts(dir:File){ receipts(dir).forEach { it.delete() } }
   fun invocationDirs()=invocationsParent.listFiles()?.filter { it.isDirectory && it.name.startsWith("inv-") } ?: emptyList()
   // Returns the child ready sentinel (which carries the child pid), or null on timeout.
   fun awaitChildStarted(dir:File,timeoutMs:Long):File? {
    val deadline=android.os.SystemClock.elapsedRealtime()+timeoutMs
    while (android.os.SystemClock.elapsedRealtime()<deadline) {
     val ready=sentinels(dir).firstOrNull()
     if (ready!=null) return ready
     Thread.sleep(100)
    }
    return null
   }
   // Harness-owned release signal. A handshake child re-reads its identity only
   // after this file appears, so releasing is a causal barrier: anything the
   // harness did before releaseChild (such as a guarded engine swap) is durably
   // before the child's late read.
   fun releaseChild(dir:File,ready:File) {
    val pid=ready.name.removePrefix("fixture-child-started-")
    File(dir,"fixture-release-"+pid).writeText("release")
   }
   // Bounded output drain + exit wait + guaranteed bounded reap. A hung child can
   // never stall the harness past these bounds; a forced destroy is always
   // followed by a bounded reap, and a timed-out reader thread is closed,
   // interrupted and bounded-joined. Every expired bound fails the probe hard.
   fun drainBounded(process:Process,exitTimeoutMs:Long,reapTimeoutMs:Long):String {
    val output=StringBuilder()
    var drainError:Throwable?=null
    val reader=thread(start=true,name="bounded-drain") {
     try { process.inputStream.bufferedReader().forEachLine { output.append(it).append('\n') } }
     catch(t:Throwable) { drainError=t }
    }
    try {
     val exited=process.waitFor(exitTimeoutMs,java.util.concurrent.TimeUnit.MILLISECONDS)
     if (!exited) {
      process.destroyForcibly()
      val reaped=process.waitFor(reapTimeoutMs,java.util.concurrent.TimeUnit.MILLISECONDS)
      check(reaped) { "child not reaped within ${reapTimeoutMs}ms after destroyForcibly" }
      throw IllegalStateException("child exceeded the ${exitTimeoutMs}ms exit bound; destroyed and reaped")
     }
     reader.join(reapTimeoutMs)
     check(!reader.isAlive) { "output drain thread still alive ${reapTimeoutMs}ms after child exit" }
     drainError?.let { throw IllegalStateException("output drain failed",it) }
     return output.toString()
    } finally {
     // Reader teardown attempts must run even when the wait/reap/checks above
     // threw: try closing the stream, interrupt, and bounded-join, then verify
     // the reader is actually gone. These are bounded attempts; a surviving
     // reader fails the probe rather than being declared handled.
     if (reader.isAlive) {
      try { process.inputStream.close() } catch (_:Throwable) {}
      reader.interrupt()
      reader.join(reapTimeoutMs)
      check(!reader.isAlive) { "output drain thread outlived close/interrupt attempts and the bounded teardown join" }
     }
    }
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
   // Engine lifecycle qualification: real production paths, synthetic zipapp fixtures, real bundled CPython.
   probe("Engine.installAndInit") {
    YoutubeDL.init(applicationContext)
    val installed=File(ytdlpDir,"yt-dlp")
    check(installed.isFile) { "engine not installed" }
    check(sha256(installed)==fixtureSha()) { "installed engine differs from the bundled fixture resource" }
   }
   probe("Engine.measureInstalledEngine") {
    val plugin=File(ytdlpDir,"yt-dlp-plugins/qual_plugin.py")
    plugin.parentFile!!.mkdirs()
    val pluginBytes="# qualification fixture plugin\n".toByteArray()
    plugin.writeBytes(pluginBytes)
    val expectation=YoutubeDL.measureInstalledEngine()
    check(expectation.engineSha256==fixtureSha()) { "measured engine digest differs from the fixture" }
    check(expectation.pluginFiles==mapOf("qual_plugin.py" to sha256(pluginBytes))) { "unexpected plugin layout: ${expectation.pluginFiles}" }
   }
   probe("Engine.launchApprovedExpectation") {
    val expectation=YoutubeDL.measureInstalledEngine()
    val info=YoutubeDL.getInfo(YoutubeDLRequest("https://fixture.invalid/ok"),expectation,LaunchCancellation())
    check(info.id=="fixture-engine-id") { "unexpected engine identity: ${info.id}" }
   }
   probe("Engine.launchExpectationMismatch") {
    val before=invocationDirs()
    val bogus=EngineExpectation("0".repeat(64),emptyMap())
    var refused=false
    try { YoutubeDL.getInfo(YoutubeDLRequest("https://fixture.invalid/mismatch"),bogus,LaunchCancellation()) }
    catch(t:Throwable) { refused=t.javaClass==EngineChangedException::class.java }
    check(refused) { "launch with an unapproved expectation was not refused" }
    // Invocation-dir equality is a NO-LEFTOVER receipt only: it proves the refusal
    // left no state behind, not that no child process ever briefly began. The
    // refusal receipt itself is the typed EngineChangedException above.
    check(invocationDirs()==before) { "refused launch left invocation artifacts: ${invocationDirs()}" }
   }
   probe("Engine.cancelBeforeStart") {
    val before=invocationDirs()
    val expectation=YoutubeDL.measureInstalledEngine()
    val token=LaunchCancellation();token.cancel()
    var refused=false
    try { YoutubeDL.getInfo(YoutubeDLRequest("https://fixture.invalid/cancel"),expectation,token) }
    catch(t:Throwable) { refused=t.javaClass==YoutubeDL.CanceledException::class.java }
    check(refused) { "pre-cancelled launch was not stopped" }
    // Same bound as above: a no-leftover receipt, not proof no child ever began.
    check(invocationDirs()==before) { "pre-cancelled launch left invocation artifacts: ${invocationDirs()}" }
   }
   probe("Engine.cancelInFlight") {
    clearReceipts(invocationsParent)
    val expectation=YoutubeDL.measureInstalledEngine()
    val token=LaunchCancellation()
    var failure:Throwable?=null
    // Ready is published atomically (fully written and closed, then renamed), so
    // observing it never catches a half-written file. The child then waits for an
    // explicit harness-owned release file with no autonomous successful exit: it
    // leaves the wait only via the release file, external termination, or a
    // handshake error that leaves a durable sibling receipt (exit 3); an error
    // whose receipt cannot be recorded parks the child instead of exiting
    // unobservably (a parked child fails the bounded joins). The harness NEVER
    // writes the release before the assertions below, so a finish soon after
    // cancel() is either destruction or a recorded error - and the recorded-error
    // case is explicitly rejected by the receipt check below.
    val runner=thread(start=true) {
     try {
      val request=YoutubeDLRequest("https://fixture.invalid/slow").addCommands(listOf("--fixture-handshake"))
      YoutubeDL.getInfo(request,expectation,token)
     } catch(t:Throwable) { failure=t }
    }
    var ready:File?=null
    val cleanup=mutableListOf<String>()
    try {
     ready=awaitChildStarted(invocationsParent,20000)
     check(ready!=null) { "child did not signal start within 20s; cannot prove in-flight destroy" }
     token.cancel()
     val cancelAt=android.os.SystemClock.elapsedRealtime()
     runner.join(15000)
     val sinceCancel=android.os.SystemClock.elapsedRealtime()-cancelAt
     check(!runner.isAlive) { "cancelled launch did not finish within 15s of cancel()" }
     check(failure!=null&&failure!!.javaClass==YoutubeDL.CanceledException::class.java) { "expected CanceledException, got $failure" }
     // The child cannot exit on its own; a finish within 12s of the cancel()
     // call is either destruction or a recorded error, and the receipt check
     // below rejects the error case.
     check(sinceCancel<12000) { "launch ran ${sinceCancel}ms after cancel(); child was not destroyed" }
     // Reject any durable handshake-error receipt: a child that errored after its
     // ready signal and was then cancelled must not satisfy this proof.
     check(handshakeErrors(invocationsParent).isEmpty()) { "handshake error receipt present: ${handshakeErrors(invocationsParent).map { it.name }}" }
    } finally {
     // Bounded fallback cleanup: every step is attempted independently and every
     // failure is aggregated and reported; none is accepted as proof of anything.
     try { token.cancel();runner.join(30000) } catch(t:Throwable) { cleanup+="final cancel/join threw: $t" }
     if (runner.isAlive) {
      cleanup+="runner thread alive after the final 30s cancel join"
      // Release the waiting child so it can exit, then re-join with a bound.
      try { ready?.let { releaseChild(invocationsParent,it) };runner.join(15000) } catch(t:Throwable) { cleanup+="release fallback threw: $t" }
      if (runner.isAlive) cleanup+="runner thread STILL alive after the release fallback"
     }
     try { clearReceipts(invocationsParent) } catch(t:Throwable) { cleanup+="receipt cleanup threw: $t" }
     if (cleanup.isNotEmpty()) throw IllegalStateException(cleanup.joinToString("; "))
    }
   }
   probe("Engine.snapshotIsolatedFromSwap") {
    clearReceipts(invocationsParent)
    val expectation=YoutubeDL.measureInstalledEngine()
    val installed=File(ytdlpDir,"yt-dlp")
    val originalBytes=installed.readBytes()
    val swappedBytes=resources.openRawResource(R.raw.ytdlp_swapped).use { it.readBytes() }
    var observedId:String?=null
    var failure:Throwable?=null
    val token=LaunchCancellation()
    val runner=thread(start=true) {
     try {
      val request=YoutubeDLRequest("https://fixture.invalid/swap").addCommands(listOf("--fixture-handshake"))
      observedId=YoutubeDL.getInfo(request,expectation,token).id
     } catch(t:Throwable) { failure=t }
    }
    var ready:File?=null
    val cleanup=mutableListOf<String>()
    try {
     ready=awaitChildStarted(invocationsParent,20000)
     check(ready!=null) { "child did not signal start within 20s" }
     // Swap the installed engine under the guard, exactly like a real updater,
     // THEN release the child. The release is a causal barrier: the child's late
     // identity read is provably after the swap, not probably-after under timing
     // assumptions, no matter how long the parent was delayed before this point.
     EngineGuard.exclusive { installed.writeBytes(swappedBytes) }
     releaseChild(invocationsParent,ready!!)
     runner.join(25000)
     check(!runner.isAlive) { "swap probe launch did not finish" }
     if (failure!=null) throw failure!!
     check(observedId=="fixture-engine-id") { "launch returned $observedId after the installed engine was swapped; snapshot isolation violated" }
    } finally {
     // Cleanup steps are independent: a stuck runner must not suppress the
     // guarded engine restore or the receipt cleanup. All failures aggregate.
     try { token.cancel();runner.join(30000) } catch(t:Throwable) { cleanup+="final cancel/join threw: $t" }
     if (runner.isAlive) {
      cleanup+="runner thread alive after the final 30s join"
      try { ready?.let { releaseChild(invocationsParent,it) };runner.join(15000) } catch(t:Throwable) { cleanup+="release fallback threw: $t" }
      if (runner.isAlive) cleanup+="runner thread STILL alive after the release fallback"
     }
     try { EngineGuard.exclusive { installed.writeBytes(originalBytes) } } catch(t:Throwable) { cleanup+="engine restore threw: $t" }
     try { clearReceipts(invocationsParent) } catch(t:Throwable) { cleanup+="receipt cleanup threw: $t" }
     if (cleanup.isNotEmpty()) throw IllegalStateException(cleanup.joinToString("; "))
    }
    check(sha256(installed)==sha256(originalBytes)) { "installed engine bytes not restored after the probe" }
   }
   probe("Engine.snapshotDiscriminationControl") {
    // Harness-only negative control: run the SAME fixture directly from the
    // INSTALLED path (no production snapshot) and swap it mid-flight, releasing
    // only after the guarded swap. The late identity read MUST observe the swap;
    // if it did not, the isolation probe above could not discriminate snapshot
    // from live-path execution.
    clearReceipts(base)
    val installed=File(ytdlpDir,"yt-dlp")
    val originalBytes=installed.readBytes()
    val swappedBytes=resources.openRawResource(R.raw.ytdlp_swapped).use { it.readBytes() }
    val pythonDir=File(noBackupFilesDir,"youtubedl-android/packages/python")
    val python=File(applicationInfo.nativeLibraryDir,"libpython.so")
    check(python.isFile) { "python payload missing" }
    val work=File(base,"direct-control-"+android.os.SystemClock.elapsedRealtimeNanos());work.mkdirs()
    val builder=ProcessBuilder(python.absolutePath,installed.absolutePath,"--fixture-handshake","--dump-json","https://fixture.invalid/control")
    builder.environment().apply {
     this["LD_LIBRARY_PATH"]=pythonDir.absolutePath+"/usr/lib"
     this["PYTHONHOME"]=pythonDir.absolutePath+"/usr"
     this["HOME"]=pythonDir.absolutePath+"/usr"
     this["TMPDIR"]=work.absolutePath
     this["SSL_CERT_FILE"]=pythonDir.absolutePath+"/usr/etc/tls/cert.pem"
    }
    builder.redirectErrorStream(true)
    var process:Process?=null
    val cleanup=mutableListOf<String>()
    try {
     process=builder.start()
     val ready=awaitChildStarted(base,20000)
     check(ready!=null) { "control child did not signal start within 20s" }
     EngineGuard.exclusive { installed.writeBytes(swappedBytes) }
     releaseChild(base,ready!!)
     // Bounded drain + exit wait + guaranteed bounded reap: the released child
     // exits at once, so a 30s exit bound is generous and a hung child can never
     // stall the harness up to the workflow ceiling.
     val output=drainBounded(process!!,30000,5000)
     check(process!!.exitValue()==0) { "control child failed: $output" }
     val id=ObjectMapper().readTree(output.trim().lineSequence().last())["id"].asText()
     check(id=="swapped-engine-id") { "direct-path child returned $id after the swap; fixture cannot discriminate live-path execution" }
    } finally {
     // Cleanup steps are independent. The reap attempt itself can throw, so it is
     // wrapped in its own try: any failure records unreaped conservatively (the
     // working directory of a possibly-live child is never deleted) and can never
     // skip the guarded engine restore or the receipt cleanup. All failures are
     // aggregated and reported together.
     val running=process
     var unreaped=false
     if (running!=null&&running.isAlive) {
      try {
       running.destroyForcibly()
       val reaped=running.waitFor(5000,java.util.concurrent.TimeUnit.MILLISECONDS)
       if (!reaped) { unreaped=true;cleanup+="control child could not be reaped within 5s of destroyForcibly; keeping its working directory" }
      } catch(t:Throwable) { unreaped=true;cleanup+="control child reap attempt threw: $t; keeping its working directory" }
     }
     try { EngineGuard.exclusive { installed.writeBytes(originalBytes) } } catch(t:Throwable) { cleanup+="engine restore threw: $t" }
     try { clearReceipts(base) } catch(t:Throwable) { cleanup+="receipt cleanup threw: $t" }
     if (!unreaped) work.deleteRecursively()
     if (cleanup.isNotEmpty()) throw IllegalStateException(cleanup.joinToString("; "))
    }
    check(sha256(installed)==sha256(originalBytes)) { "installed engine bytes not restored after the control" }
   }
   probe("Engine.incompleteMarkerRefusal") {
    val marker=File(ytdlpDir,"yt-dlp.update-incomplete")
    marker.writeText("seali-engine-update-v1\nyt-dlp.rollback-"+"0".repeat(64)+"\n"+"0".repeat(64)+"\n")
    try {
     var measureRefused=false
     try { YoutubeDL.measureInstalledEngine() }
     catch(t:Throwable) { measureRefused=t.javaClass==YoutubeDLException::class.java }
     check(measureRefused) { "measure was not refused while the update marker exists" }
     var launchRefused=false
     try { YoutubeDL.getInfo(YoutubeDLRequest("https://fixture.invalid/marker")) }
     catch(t:Throwable) { launchRefused=t.javaClass==YoutubeDLException::class.java }
     check(launchRefused) { "launch was not refused while the update marker exists" }
    } finally { marker.delete() }
    YoutubeDL.measureInstalledEngine()
   }
   probe("Engine.guardRebindRefusal") {
    var refused=false
    try { EngineGuard.bind(File(filesDir,"other-engine-dir")) }
    catch(t:Throwable) { refused=t.javaClass==YoutubeDLException::class.java }
    check(refused) { "EngineGuard accepted a second engine directory" }
    YoutubeDL.measureInstalledEngine()
   }
   probe("Engine.cleanupAccounting") {
    check(YoutubeDL.launchCleanupFailures()==0) { "launch cleanup failures: ${YoutubeDL.launchCleanupFailures()}" }
    val leftovers=(invocationDirs()+receipts(invocationsParent))
    check(leftovers.isEmpty()) { "leaked launch artifacts: ${leftovers.map { it.name }}" }
   }
   val names=checks.keys().asSequence().toList()
   result.put("pass",names.size==22 && names.all { checks.getBoolean(it) }).put("complete",true)
   persist()
   val display=result.toString(2)
   runOnUiThread {
    setContentView(ScrollView(this@SmokeActivity).apply { addView(TextView(this@SmokeActivity).apply { text=display;setPadding(24,24,24,24) }) })
   }
  }
 }
}
