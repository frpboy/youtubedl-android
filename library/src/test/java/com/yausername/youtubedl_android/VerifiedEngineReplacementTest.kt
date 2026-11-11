package com.yausername.youtubedl_android

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class VerifiedEngineReplacementTest {
    private fun fixture(test: (File, File) -> Unit) {
        val root = Files.createTempDirectory("engine-update-test-").toFile()
        try {
            val installed = File(root, "yt-dlp").apply { writeText("old-engine") }
            test(root, installed)
        } finally { root.deleteRecursively() }
    }
    private fun newHash(root: File): String {
        val file = File(root, "new-fixture").apply { writeText("new-engine") }
        return VerifiedEngineReplacement.digest(file).also { file.delete() }
    }
    @Test fun verifiedReplaceChecksBeforeAndAfterAndRetainsOldHashBackup() = fixture { root, installed ->
        var checks = 0
        VerifiedEngineReplacement.replace(installed, newHash(root), { it.writeText("new-engine") }, {
            assertEquals("new-engine", it.readText()); checks++
        })
        assertEquals(2, checks)
        assertEquals("new-engine", installed.readText())
        assertEquals("old-engine", root.listFiles()!!.single { it.name.startsWith("yt-dlp.rollback-") }.readText())
    }
    @Test fun wrongDigestPreservesOldEngine() = fixture { root, installed ->
        assertTrue(runCatching { VerifiedEngineReplacement.replace(installed, newHash(root), { it.writeText("wrong") }, { fail() }) }.isFailure)
        assertEquals("old-engine", installed.readText())
    }
    @Test fun failedDownloadPreservesOldEngineAndCleansTemporary() = fixture { root, installed ->
        assertTrue(runCatching { VerifiedEngineReplacement.replace(installed, newHash(root), { it.writeText("partial"); error("network") }, {}) }.isFailure)
        assertEquals("old-engine", installed.readText())
        assertEquals(listOf("yt-dlp"), root.list()!!.toList())
    }
    @Test fun failedStagedCapabilitiesNeverReplace() = fixture { root, installed ->
        assertTrue(runCatching { VerifiedEngineReplacement.replace(installed, newHash(root), { it.writeText("new-engine") }, { error("missing EJS") }) }.isFailure)
        assertEquals("old-engine", installed.readText())
    }
    @Test fun failedPostInstallCapabilitiesRestoresOldEngine() = fixture { root, installed ->
        var checks = 0
        assertTrue(runCatching { VerifiedEngineReplacement.replace(installed, newHash(root), { it.writeText("new-engine") }, {
            if (++checks == 2) error("post-install")
        }) }.isFailure)
        assertEquals(2, checks)
        assertEquals("old-engine", installed.readText())
        assertTrue(root.list()!!.any { it.startsWith("yt-dlp.rollback-") })
    }
    @Test fun failedAtomicMoveNeverDeletesInstalledBytes() = fixture { root, installed ->
        assertTrue(runCatching { VerifiedEngineReplacement.replace(installed, newHash(root), { it.writeText("new-engine") }, {}, { _, _ -> false }) }.isFailure)
        assertEquals("old-engine", installed.readText())
    }
    @Test fun missingReleaseDigestRejectsBeforeDownload() = fixture { _, installed ->
        assertTrue(runCatching { VerifiedEngineReplacement.replace(installed, "", { fail() }, {}) }.isFailure)
        assertEquals("old-engine", installed.readText())
    }
    @Test fun rollbackTamperRejectsBeforeReplace() = fixture { root, installed ->
        File(root, "yt-dlp.rollback-${VerifiedEngineReplacement.digest(installed)}").writeText("tampered")
        assertTrue(runCatching { VerifiedEngineReplacement.replace(installed, newHash(root), { it.writeText("new-engine") }, {}) }.isFailure)
        assertEquals("old-engine", installed.readText())
    }
    @Test fun interruptedUpdateMarkerBlocksFurtherReplacementAndExecution() = fixture { root, installed ->
        File(root, "yt-dlp.update-incomplete").writeText("retained rollback")
        assertTrue(runCatching { VerifiedEngineReplacement.requireNoIncompleteUpdate(installed) }.isFailure)
        assertTrue(runCatching { VerifiedEngineReplacement.replace(installed, newHash(root), { fail() }, {}) }.isFailure)
        assertEquals("old-engine", installed.readText())
    }
    @Test fun failedRollbackRetainsMarkerAndBackupInsteadOfClaimingSafeExecution() = fixture { root, installed ->
        var checks = 0
        val result = runCatching {
            VerifiedEngineReplacement.replace(installed, newHash(root), { it.writeText("new-engine") }, {
                if (++checks == 2) error("post-install")
            }, { from, to -> if (from.name.startsWith(".yt-dlp-restore-")) false else from.renameTo(to) })
        }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.suppressed.isNotEmpty())
        assertTrue(VerifiedEngineReplacement.pendingMarker(installed).exists())
        assertTrue(runCatching { VerifiedEngineReplacement.requireNoIncompleteUpdate(installed) }.isFailure)
        assertEquals("old-engine", root.listFiles()!!.single { it.name.startsWith("yt-dlp.rollback-") }.readText())
    }
}
