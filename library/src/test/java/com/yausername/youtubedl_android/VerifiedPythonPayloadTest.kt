package com.yausername.youtubedl_android

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class VerifiedPythonPayloadTest {
    private fun fixture(test: (File, File, File) -> Unit) {
        val root = Files.createTempDirectory("python-payload-").toFile()
        try {
            val archive = File(root, "payload.zip").apply { writeText("new-content") }
            val python = File(root, "python").apply { mkdir() }
            File(python, "preserved.txt").writeText("old Python and any prior user-added files")
            test(root, archive, python)
        } finally { root.deleteRecursively() }
    }
    private fun extract(archive: File, stage: File) { File(stage, "payload").writeText(archive.readText()) }
    @Test fun sameSizeDifferentPayloadReplacesAndRetainsWholePreviousDirectory() = fixture { root, archive, python ->
        val old = File(root, "old.zip").apply { writeText("old-content") }
        assertEquals(old.length(), archive.length())
        File(python, ".seali-python-archive-sha256").writeText(VerifiedEngineReplacement.digest(old))
        assertTrue(VerifiedPythonPayload.install(archive, python, ::extract))
        assertEquals("new-content", File(python, "payload").readText())
        val retained = root.listFiles()!!.single { it.name.startsWith("python-retained-") }
        assertEquals("old Python and any prior user-added files", File(retained, "preserved.txt").readText())
    }
    @Test fun matchingActualArchiveHashDoesNotExtractAgain() = fixture { _, archive, python ->
        File(python, ".seali-python-archive-sha256").writeText(VerifiedEngineReplacement.digest(archive))
        assertFalse(VerifiedPythonPayload.install(archive, python, { _, _ -> fail() }))
    }
    @Test fun failedExtractionPreservesOriginalAndRetainsStageWithoutMarker() = fixture { root, archive, python ->
        assertTrue(runCatching { VerifiedPythonPayload.install(archive, python, { _, stage ->
            File(stage, "partial").writeText("partial"); error("bad archive")
        }) }.isFailure)
        assertTrue(File(python, "preserved.txt").isFile)
        val stage = root.listFiles()!!.single { it.name.startsWith(".python-staged-") }
        assertFalse(File(stage, ".seali-python-archive-sha256").exists())
    }
    @Test fun failedFinalDirectoryMoveRestoresPreviousDirectory() = fixture { _, archive, python ->
        assertTrue(runCatching { VerifiedPythonPayload.install(archive, python, ::extract, { from, to ->
            if (from.name.startsWith(".python-staged-")) false else from.renameTo(to)
        }) }.isFailure)
        assertTrue(File(python, "preserved.txt").isFile)
    }
    @Test fun failedRetainMoveNeverOverwritesOriginal() = fixture { _, archive, python ->
        assertTrue(runCatching { VerifiedPythonPayload.install(archive, python, ::extract, { _, _ -> false }) }.isFailure)
        assertTrue(File(python, "preserved.txt").isFile)
    }
    @Test fun legacySizePreferenceWithoutHashMarkerCannotSkipExtraction() = fixture { _, archive, python ->
        var called = false
        assertTrue(VerifiedPythonPayload.install(archive, python, { a, stage -> called = true; extract(a, stage) }))
        assertTrue(called)
        assertEquals(VerifiedEngineReplacement.digest(archive), File(python, ".seali-python-archive-sha256").readText())
    }
    @Test fun legitimateParentAliasIsAccepted() {
        val root = Files.createTempDirectory("python-alias-").toFile()
        try {
            val real = File(root, "real").apply { mkdir() }
            val alias = File(root, "alias")
            Files.createSymbolicLink(alias.toPath(), real.toPath())
            val archive = File(root, "payload.zip").apply { writeText("new-content") }
            val python = File(alias, "python")
            assertTrue(VerifiedPythonPayload.install(archive, python, ::extract))
            assertEquals("new-content", File(File(real, "python"), "payload").readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun liveLeafSymlinkIsRejected() {
        val root = Files.createTempDirectory("python-livesymlink-").toFile()
        try {
            val real = File(root, "real-python").apply { mkdir() }
            File(real, "preserved.txt").writeText("must survive")
            val link = File(root, "python")
            Files.createSymbolicLink(link.toPath(), real.toPath())
            val archive = File(root, "payload.zip").apply { writeText("new-content") }
            val failure = runCatching { VerifiedPythonPayload.install(archive, link, ::extract) }
            assertTrue(failure.isFailure)
            assertTrue(failure.exceptionOrNull()!!.message!!.contains("must not be a symlink"))
            assertEquals("must survive", File(real, "preserved.txt").readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun danglingLeafSymlinkIsRejected() {
        val root = Files.createTempDirectory("python-danglingsymlink-").toFile()
        try {
            val link = File(root, "python")
            Files.createSymbolicLink(link.toPath(), File(root, "missing-target").toPath())
            val archive = File(root, "payload.zip").apply { writeText("new-content") }
            val failure = runCatching { VerifiedPythonPayload.install(archive, link, ::extract) }
            assertTrue(failure.isFailure)
            assertTrue(failure.exceptionOrNull()!!.message!!.contains("must not be a symlink"))
        } finally { root.deleteRecursively() }
    }
    @Test fun danglingMarkerSymlinkIsRejected() {
        val root = Files.createTempDirectory("python-markersymlink-").toFile()
        try {
            val python = File(root, "python").apply { mkdir() }
            Files.createSymbolicLink(File(python, ".seali-python-archive-sha256").toPath(), File(root, "missing-marker-target").toPath())
            val archive = File(root, "payload.zip").apply { writeText("new-content") }
            val failure = runCatching { VerifiedPythonPayload.install(archive, python, ::extract) }
            assertTrue(failure.isFailure)
            assertTrue(failure.exceptionOrNull()!!.message!!.contains("marker must not be a symlink"))
        } finally { root.deleteRecursively() }
    }
}
