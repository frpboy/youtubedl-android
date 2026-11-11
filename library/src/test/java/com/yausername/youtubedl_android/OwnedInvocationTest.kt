package com.yausername.youtubedl_android
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class OwnedInvocationTest {
 @get:Rule val tmp = TemporaryFolder()
 @Test fun uniqueDirectoriesPreserveOtherTasks() {
  val a=OwnedInvocation.create(tmp.root);val b=OwnedInvocation.create(tmp.root)
  assertNotEquals(a.directory,b.directory)
  File(a.directory,"fixture.js").writeText("a")
  File(b.directory,"fixture.js").writeText("b")
  assertTrue(a.cleanAfterExit());assertTrue(File(b.directory,"fixture.js").exists());assertTrue(b.cleanAfterExit())
 }
 @Test fun cancelBeforeAttachDestroysOnlyOwnedProcess() {
  val a=OwnedInvocation.create(tmp.root);assertTrue(a.cancel());assertFalse(a.cancel())
  val p=ProcessBuilder("/bin/sleep","5").start();a.attach(p)
  assertTrue(a.stopAndWait());assertTrue(a.wasCancelled());assertTrue(a.cleanAfterExit())
 }
 @Test fun liveProcessAndUnexpectedFilesAreNeverDeleted() {
  val a=OwnedInvocation.create(tmp.root);val p=ProcessBuilder("/bin/sleep","5").start();a.attach(p)
  File(a.directory,"fixture.js").writeText("test");assertFalse(a.cleanAfterExit());assertTrue(File(a.directory,"fixture.js").exists())
  assertTrue(a.stopAndWait());File(a.directory,"user-data.bin").writeText("preserve")
  assertFalse(a.cleanAfterExit());assertTrue(File(a.directory,"user-data.bin").exists());assertFalse(File(a.directory,"fixture.js").exists())
 }
 @Test fun symlinkEscapeNotFollowed() {
  val a=OwnedInvocation.create(tmp.root);val outside=tmp.newFile("outside.js");outside.writeText("preserve")
  java.nio.file.Files.createSymbolicLink(File(a.directory,"linked.js").toPath(),outside.toPath())
  assertFalse(a.cleanAfterExit());assertEquals("preserve",outside.readText());assertTrue(File(a.directory,"linked.js").exists())
 }
 @Test fun noStartupSweepDeletesPriorInvocation() {
  val a=OwnedInvocation.create(tmp.root);File(a.directory,"stale.js").writeText("preserve")
  val b=OwnedInvocation.create(tmp.root);assertTrue(File(a.directory,"stale.js").exists());assertTrue(b.cleanAfterExit())
 }
 @Test fun aliasedCacheRootAcceptedButInvocationLeafAliasRejected() {
  val real=tmp.newFolder("real-cache");val alias=File(tmp.root,"cache-alias")
  java.nio.file.Files.createSymbolicLink(alias.toPath(),real.toPath())
  val a=OwnedInvocation.create(alias);File(a.directory,"fixture.js").writeText("test")
  assertTrue(a.cleanAfterExit())
  val unsafe=tmp.newFolder("unsafe-cache");val external=tmp.newFolder("other")
  java.nio.file.Files.createSymbolicLink(File(unsafe,"seali-owned-invocations").toPath(),external.toPath())
  try { OwnedInvocation.create(unsafe);fail("leaf alias accepted") } catch (_: java.io.IOException) { }
 }
 @Test fun startupFailureBeforeProcessStillCleansOnlyOwnedDirectory() {
  val a=OwnedInvocation.create(tmp.root)
  try { ProcessBuilder("/definitely-missing-seali-fixture").start();fail("must fail") }
  catch (_: java.io.IOException) { assertTrue(a.cleanAfterExit()) }
 }
 private class ControlledProcess(var running: Boolean = true) : Process() {
  var destroys=0
  override fun destroy() { destroys++ }
  override fun exitValue(): Int { if(running) throw IllegalThreadStateException();return 0 }
  override fun waitFor(): Int = exitValue()
  override fun getOutputStream()=java.io.ByteArrayOutputStream()
  override fun getInputStream()=java.io.ByteArrayInputStream(byteArrayOf())
  override fun getErrorStream()=java.io.ByteArrayInputStream(byteArrayOf())
 }
 @Test fun ignoredDestroyTimesOutAndRetainsDirectory() {
  val a=OwnedInvocation.create(tmp.root);val p=ControlledProcess();a.attach(p)
  File(a.directory,"fixture.js").writeText("preservewhilelive")
  assertFalse(a.stopAndWait());assertFalse(a.cleanAfterExit());assertTrue(a.directory.exists())
  p.running=false;assertTrue(a.cleanAfterExit())
 }
 @Test fun interruptedWaitPreservesInterruptAndLiveDirectory() {
  val a=OwnedInvocation.create(tmp.root);val p=ControlledProcess();a.attach(p)
  Thread.currentThread().interrupt()
  try { assertFalse(a.stopAndWait());assertTrue(Thread.currentThread().isInterrupted);assertFalse(a.cleanAfterExit()) }
  finally { Thread.interrupted();p.running=false;a.cleanAfterExit() }
 }
 @Test fun cancellationRacingAlreadyExitedProcessRemainsOwned() {
  val a=OwnedInvocation.create(tmp.root);val p=ControlledProcess(false);a.attach(p)
  assertTrue(a.cancel());assertFalse(a.cancel());assertTrue(a.stopAndWait());assertTrue(a.wasCancelled());assertTrue(a.cleanAfterExit())
 }
}
