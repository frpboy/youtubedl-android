package com.yausername.youtubedl_android

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EngineSnapshotTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun engine(root: File, text: String = "engine-v1"): File =
        File(root, "yt-dlp").also { it.writeText(text) }

    private fun plugin(root: File, name: String = "p.py", text: String = "plugin-v1"): File {
        val dir = File(root, "yt-dlp-plugins/strictcookie/yt_dlp_plugins/postprocessor").apply { mkdirs() }
        return File(dir, name).also { it.writeText(text) }
    }

    @Test fun snapshotCopiesAndHashesEngineAndEveryPluginFile() {
        val root = tmp.newFolder("root"); val inv = tmp.newFolder("inv")
        val e = engine(root); plugin(root)
        val s = EngineSnapshots.create(e, inv)
        assertEquals(VerifiedEngineReplacement.digest(e), s.engineSha256)
        assertEquals(setOf("strictcookie/yt_dlp_plugins/postprocessor/p.py"), s.pluginFiles.keys)
        assertEquals("engine-v1", File(inv, "yt-dlp").readText())
        assertEquals("plugin-v1", File(inv, "yt-dlp-plugins/strictcookie/yt_dlp_plugins/postprocessor/p.py").readText())
        assertTrue(s.matches(EngineExpectation(s.engineSha256, s.pluginFiles)))
        assertFalse(s.matches(EngineExpectation(s.engineSha256, emptyMap())))
        assertFalse(s.matches(EngineExpectation("0".repeat(64), s.pluginFiles)))
    }

    @Test fun measureReportsExactlyWhatASnapshotWouldHoldWithoutWritingAnything() {
        val root = tmp.newFolder("root"); val inv = tmp.newFolder("inv")
        val e = engine(root); plugin(root)
        File(File(root, "yt-dlp-plugins/strictcookie/yt_dlp_plugins/postprocessor"), "__pycache__").apply { mkdirs() }.resolve("p.pyc").writeText("x")
        val before = root.walkTopDown().count()
        val m = EngineSnapshots.measure(e)
        assertEquals(before, root.walkTopDown().count())
        val s = EngineSnapshots.create(e, inv)
        assertEquals(m.engineSha256, s.engineSha256); assertEquals(m.pluginFiles, s.pluginFiles)
        assertTrue(s.matches(m))
    }

    @Test fun laterSwapOfInstalledFilesDoesNotChangeTheSnapshot() {
        val root = tmp.newFolder("root"); val inv = tmp.newFolder("inv")
        val e = engine(root); val p = plugin(root)
        val s = EngineSnapshots.create(e, inv)
        e.writeText("engine-v2"); p.writeText("plugin-v2")
        assertEquals("engine-v1", s.engine.readText())
        assertEquals("plugin-v1", File(inv, "yt-dlp-plugins/strictcookie/yt_dlp_plugins/postprocessor/p.py").readText())
    }

    @Test fun pythonBytecodeCacheIsNotPartOfTheLayoutAndExtraFilesAre() {
        val root = tmp.newFolder("root"); val inv = tmp.newFolder("inv")
        val e = engine(root); val p = plugin(root)
        File(p.parentFile, "__pycache__").apply { mkdirs() }.resolve("p.cpython-311.pyc").writeText("x")
        assertEquals(1, EngineSnapshots.create(e, inv).pluginFiles.size)
        val inv2 = tmp.newFolder("inv2")
        plugin(root, "other.py", "extra")
        assertEquals(2, EngineSnapshots.create(e, inv2).pluginFiles.size)
    }

    @Test fun symlinkedEngineOrPluginEntriesAreRefused() {
        val root = tmp.newFolder("root"); val outside = tmp.newFile("outside"); outside.writeText("secret")
        val real = engine(root)
        val link = File(root, "link-engine"); Files.createSymbolicLink(link.toPath(), real.toPath())
        assertTrue(runCatching { EngineSnapshots.create(link, tmp.newFolder("i1")) }.isFailure)
        val p = plugin(root)
        Files.createSymbolicLink(File(p.parentFile, "evil.py").toPath(), outside.toPath())
        assertTrue(runCatching { EngineSnapshots.create(real, tmp.newFolder("i2")) }.isFailure)
    }

    @Test fun removeDeletesOnlyTheSnapshotLayoutAndNeverFollowsLinks() {
        val root = tmp.newFolder("root"); val inv = tmp.newFolder("inv")
        val outside = tmp.newFile("outside.txt"); outside.writeText("keep")
        val e = engine(root); plugin(root); EngineSnapshots.create(e, inv)
        File(inv, "user-data.bin").writeText("keep")
        Files.createSymbolicLink(File(inv, "yt-dlp-plugins/strictcookie/ln").toPath(), outside.toPath())
        assertTrue(EngineSnapshots.remove(inv))
        assertFalse(File(inv, "yt-dlp").exists()); assertFalse(File(inv, "yt-dlp-plugins").exists())
        assertEquals("keep", outside.readText()); assertEquals("keep", File(inv, "user-data.bin").readText())
    }

    @Test fun invocationCleanupRemovesTheSnapshotAfterExit() {
        val root = tmp.newFolder("root"); val cache = tmp.newFolder("cache")
        val e = engine(root); plugin(root)
        val inv = OwnedInvocation.create(cache)
        EngineSnapshots.create(e, inv.directory)
        assertTrue(inv.cleanAfterExit()); assertFalse(inv.directory.exists())
    }

    @Test fun cancelBeforeBindIsRememberedAndStopsTheLaunch() {
        val cache = tmp.newFolder("cache"); val inv = OwnedInvocation.create(cache)
        val token = LaunchCancellation(); token.cancel()
        token.bind(inv)
        assertTrue(inv.wasCancelled())
        // A process that appears afterwards is destroyed on attach.
        val p = ProcessBuilder("/bin/sleep", "5").start(); inv.attach(p)
        assertTrue(inv.stopAndWait()); assertTrue(inv.cleanAfterExit())
    }

    @Test fun cancelAfterBindDestroysTheOwnedProcess() {
        val cache = tmp.newFolder("cache"); val inv = OwnedInvocation.create(cache)
        val token = LaunchCancellation(); token.bind(inv)
        val p = ProcessBuilder("/bin/sleep", "5").start(); inv.attach(p)
        token.cancel()
        assertTrue(inv.stopAndWait()); assertTrue(inv.wasCancelled()); assertTrue(inv.cleanAfterExit())
    }

    @Test fun updateSwapWaitsForTheGuardButDownloadAndFirstValidationDoNot() {
        val root = tmp.newFolder("root"); val installed = engine(root, "old-engine")
        val hash = File(root, "n").also { it.writeText("new-engine") }.let { VerifiedEngineReplacement.digest(it).also { _ -> it.delete() } }
        val holding = CountDownLatch(1); val release = CountDownLatch(1)
        val holder = Thread { EngineGuard.exclusive { holding.countDown(); release.await() } }.apply { start() }
        assertTrue(holding.await(2, TimeUnit.SECONDS))
        val downloaded = AtomicBoolean(false); val validated = AtomicBoolean(false); val finished = AtomicBoolean(false)
        val updater = Thread {
            VerifiedEngineReplacement.replace(installed, hash, { it.writeText("new-engine"); downloaded.set(true) }, { validated.set(true) })
            finished.set(true)
        }.apply { start() }
        waitUntilBlocked(updater)
        assertTrue("download must not wait for the guard", downloaded.get())
        assertTrue("first validation must not wait for the guard", validated.get())
        assertFalse("swap must wait for the guard", finished.get())
        assertEquals("old-engine", installed.readText())
        release.countDown(); updater.join(5000); holder.join(2000)
        assertTrue(finished.get()); assertEquals("new-engine", installed.readText())
    }

    @Test fun snapshotWaitsForAnInProgressWriterThenSeesOnlyWholeFiles() {
        val root = tmp.newFolder("root"); val e = engine(root, "old-engine")
        val holding = CountDownLatch(1); val release = CountDownLatch(1)
        val holder = Thread { EngineGuard.exclusive { holding.countDown(); release.await(); e.writeText("new-engine") } }.apply { start() }
        assertTrue(holding.await(2, TimeUnit.SECONDS))
        var seen = ""
        val taker = Thread { seen = EngineGuard.exclusive { EngineSnapshots.create(e, tmp.newFolder("inv")).engine.readText() } }.apply { start() }
        waitUntilBlocked(taker)
        assertEquals("", seen)
        release.countDown(); taker.join(5000); holder.join(2000)
        assertEquals("new-engine", seen)
    }

    /** Waits until the thread is parked on the guard (or finished), so the assertions that follow are not timing luck. */
    private fun waitUntilBlocked(t: Thread) {
        val end = System.nanoTime() + 5_000_000_000L
        while (t.state != Thread.State.WAITING && t.state != Thread.State.BLOCKED && t.isAlive && System.nanoTime() < end) Thread.sleep(5)
        assertTrue("thread should be waiting on the guard, was ${t.state}", t.state == Thread.State.WAITING || t.state == Thread.State.BLOCKED)
    }

    @Test fun layoutViolationsAreYoutubeDLExceptionsNotUncheckedOnes() {
        val root = tmp.newFolder("root"); val e = engine(root)
        File(root, "yt-dlp-plugins").writeText("a file, not a folder")
        assertTrue(runCatching { EngineSnapshots.create(e, tmp.newFolder("i1")) }.exceptionOrNull() is YoutubeDLException)
        File(root, "yt-dlp-plugins").delete()
        repeat(65) { plugin(root, "p$it.py", "x$it") }
        assertTrue(runCatching { EngineSnapshots.measure(e) }.exceptionOrNull() is YoutubeDLException)
        assertTrue(runCatching { EngineSnapshots.measure(File(root, "missing-engine")) }.exceptionOrNull() is YoutubeDLException)
    }

    @Test fun unreadablePluginFolderFailsClosedInsteadOfLookingEmpty() {
        val root = tmp.newFolder("root"); val e = engine(root); val p = plugin(root)
        val dir = p.parentFile
        assumeTrue("needs POSIX permissions and a non-root user", dir.setReadable(false) && !dir.canRead())
        try {
            assertTrue(runCatching { EngineSnapshots.measure(e) }.exceptionOrNull() is YoutubeDLException)
        } finally { dir.setReadable(true) }
    }

    private fun invocation() = OwnedInvocation.create(tmp.newFolder())

    @Test fun launchPathBindsTheTokenAndRefusesAPreCancelledLaunchBeforeAnySnapshot() {
        val root = tmp.newFolder("root"); val e = engine(root); val inv = invocation()
        val token = LaunchCancellation(); token.cancel()
        assertTrue(runCatching { YoutubeDL.snapshotForLaunch(e, inv, token, null) }.exceptionOrNull() is YoutubeDL.CanceledException)
        assertTrue(inv.wasCancelled())
        assertFalse(File(inv.directory, "yt-dlp").exists())
        assertTrue(runCatching { token.bind(inv) }.isFailure) // already bound by the launch path
    }

    @Test fun launchPathRefusesAnEngineThatDiffersFromTheApprovedExpectation() {
        val root = tmp.newFolder("root"); val e = engine(root); plugin(root)
        val approved = EngineSnapshots.measure(e)
        plugin(root, "p.py", "changed-after-approval")
        val inv = invocation()
        assertTrue(runCatching { YoutubeDL.snapshotForLaunch(e, inv, null, approved) }.exceptionOrNull() is EngineChangedException)
        val ok = YoutubeDL.snapshotForLaunch(e, invocation(), null, EngineSnapshots.measure(e))
        assertTrue(ok.engine.isFile)
        assertTrue(inv.cleanAfterExit())
    }

    @Test fun launchRefusesWhileAnUpdateIsIncomplete() {
        val root = tmp.newFolder("root"); val e = engine(root)
        File(root, "yt-dlp.update-incomplete").writeText("retained rollback")
        val inv = invocation()
        assertTrue(runCatching { YoutubeDL.snapshotForLaunch(e, inv, null, null) }.exceptionOrNull() is YoutubeDLException)
        assertFalse(File(inv.directory, "yt-dlp").exists())
    }

    @Test fun cancelAndStartAreOneStep() {
        val inv = invocation(); inv.cancel()
        assertTrue(runCatching { inv.start(ProcessBuilder("/bin/sleep", "5")) }.exceptionOrNull() is YoutubeDL.CanceledException)
        val inv2 = invocation()
        val p = inv2.start(ProcessBuilder("/bin/sleep", "5"))
        assertTrue(inv2.cancel()); assertTrue(inv2.stopAndWait()); assertFalse(p.isAlive)
        assertTrue(inv2.cleanAfterExit())
    }

    @Test fun guardTakesAnOsFileLockWhenBoundAndStaysReentrant() {
        val root = tmp.newFolder("root")
        EngineGuard.bind(root)
        try {
            val result = EngineGuard.exclusive { EngineGuard.exclusive { 42 } }
            assertEquals(42, result)
            assertTrue(File(root, ".engine-guard.lock").exists())
            // Taken and released: a second thread gets it straight away.
            val done = AtomicBoolean(false)
            Thread { EngineGuard.exclusive { done.set(true) } }.apply { start(); join(2000) }
            assertTrue(done.get())
        } finally { EngineGuard.unbind() }
    }

    private fun zipWith(file: File, late: String) {
        ZipOutputStream(file.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("__main__.py"))
            z.write("import sys,time\nsys.stdout.write('start\\n');sys.stdout.flush()\ntime.sleep(2)\nimport late\nprint('late',late.WHO)\n".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("pad.py")); z.write(ByteArray(if (late == "NEW") 5000 else 10) { '#'.code.toByte() }); z.closeEntry()
            z.putNextEntry(ZipEntry("late.py")); z.write("WHO='$late'\n".toByteArray()); z.closeEntry()
        }
    }

    private fun runSwapWhileChildRuns(useSnapshot: Boolean): Pair<Int, String> {
        val root = tmp.newFolder(); val inv = tmp.newFolder()
        val e = File(root, "yt-dlp"); zipWith(e, "OLD")
        val run = if (useSnapshot) EngineSnapshots.create(e, inv).engine else e
        val p = ProcessBuilder("python3", run.absolutePath).redirectErrorStream(true).start()
        assertEquals("start", p.inputStream.bufferedReader().readLine())
        val next = File(root, "yt-dlp.new"); zipWith(next, "NEW")
        assertTrue(next.renameTo(e))
        val out = p.inputStream.bufferedReader().readText().trim()
        return p.waitFor() to out
    }

    @Test fun realPythonChildKeepsRunningFromItsSnapshotWhileTheInstalledEngineIsSwapped() {
        val have = runCatching { ProcessBuilder("python3", "--version").start().waitFor() == 0 }.getOrDefault(false)
        assumeTrue("python3 not available", have)
        val (codeUnprotected, _) = runSwapWhileChildRuns(false)
        assertNotEquals("control: an in-place swap must break an unprotected running child", 0, codeUnprotected)
        val (codeSnap, out) = runSwapWhileChildRuns(true)
        assertEquals(out, 0, codeSnap); assertEquals("late OLD", out)
    }
}
