package com.yausername.youtubedl_android

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class EngineRestartRecoveryTest {
    private fun fixture(test: (File, File, String, File) -> Unit) {
        val root=Files.createTempDirectory("engine-restart-").toFile()
        try {
            val installed=File(root,"yt-dlp").apply { writeText("old-engine") }
            val hash=VerifiedEngineReplacement.digest(installed)
            val retained=File(root,"yt-dlp.rollback-$hash").apply { writeText("old-engine") }
            test(root,installed,hash,retained)
        } finally { root.deleteRecursively() }
    }
    private fun pinnedHash(root:File):String {
        val f=File(root,"pinned").apply { writeText("pinned-engine") }
        return VerifiedEngineReplacement.digest(f).also { f.delete() }
    }
    private fun journal(installed:File, retained:File) {
        VerifiedEngineReplacement.pendingMarker(installed).writeText("seali-engine-update-v1\n${retained.name}\n${"a".repeat(64)}\n")
    }
    @Test fun killedAfterMarkerWriteRecoversOldEngine() = fixture { root, installed, _, retained ->
        journal(installed,retained)
        val r=VerifiedEngineReplacement.recover(installed,pinnedHash(root),{fail()})!!
        assertFalse(r.usedPinnedFallback);assertEquals("old-engine",installed.readText())
        VerifiedEngineReplacement.requireNoIncompleteUpdate(installed)
    }
    @Test fun killedAfterMoveRecoversOldEngineWithoutDeletingCandidateEvidence() = fixture { root, installed, _, retained ->
        journal(installed,retained);installed.writeText("new-engine")
        val candidateHash=VerifiedEngineReplacement.digest(installed)
        VerifiedEngineReplacement.recover(installed,pinnedHash(root),{fail()})
        assertEquals("old-engine",installed.readText())
        assertEquals("new-engine",root.listFiles()!!.single { it.name.startsWith("yt-dlp.recovery-evidence-$candidateHash-") }.readText())
    }
    @Test fun killedAfterPostValidationBeforeMarkerDeleteStillRecovers() = fixture { root, installed, _, retained ->
        journal(installed,retained);installed.writeText("new-engine")
        assertNotNull(VerifiedEngineReplacement.recover(installed,pinnedHash(root),{fail()}))
        assertNull(VerifiedEngineReplacement.recover(installed,pinnedHash(root),{fail()}))
    }
    @Test fun markerDeleteFailureCanRecoverOnNextRestart() = fixture { root, installed, _, retained ->
        journal(installed,retained);installed.writeText("new-engine")
        assertTrue(runCatching { VerifiedEngineReplacement.recover(installed,pinnedHash(root),{fail()},removeMarker={false}) }.isFailure)
        assertTrue(VerifiedEngineReplacement.pendingMarker(installed).exists())
        VerifiedEngineReplacement.recover(installed,pinnedHash(root),{fail()})
        VerifiedEngineReplacement.requireNoIncompleteUpdate(installed)
    }
    @Test fun missingRetainedEngineUsesHashCheckedPinnedAsset() = fixture { root, installed, _, retained ->
        journal(installed,retained);retained.delete()
        val r=VerifiedEngineReplacement.recover(installed,pinnedHash(root),{it.writeText("pinned-engine")})!!
        assertTrue(r.usedPinnedFallback);assertEquals("pinned-engine",installed.readText())
    }
    @Test fun tamperedRetainedEngineUsesPinnedAsset() = fixture { root, installed, _, retained ->
        journal(installed,retained);retained.writeText("corrupt")
        assertTrue(VerifiedEngineReplacement.recover(installed,pinnedHash(root),{it.writeText("pinned-engine")})!!.usedPinnedFallback)
    }
    @Test fun unusableRetainedAndWrongPinnedBytesFailClosedPreservingJournal() = fixture { root, installed, _, retained ->
        journal(installed,retained);retained.delete()
        assertTrue(runCatching { VerifiedEngineReplacement.recover(installed,pinnedHash(root),{it.writeText("wrong")}) }.isFailure)
        assertTrue(VerifiedEngineReplacement.pendingMarker(installed).exists());assertEquals("old-engine",installed.readText())
    }
    @Test fun pathTraversalJournalNeverReadsExternalPathAndUsesPinnedAsset() = fixture { root, installed, _, _ ->
        VerifiedEngineReplacement.pendingMarker(installed).writeText("seali-engine-update-v1\n../../private\n${"a".repeat(64)}\n")
        assertTrue(VerifiedEngineReplacement.recover(installed,pinnedHash(root),{it.writeText("pinned-engine")})!!.usedPinnedFallback)
    }
    @Test fun symlinkRetainedEngineCannotBeUsedForRecovery() = fixture { root, installed, _, retained ->
        journal(installed,retained);retained.delete()
        val external=File(root,"external").apply { writeText("old-engine") }
        Files.createSymbolicLink(retained.toPath(),external.toPath())
        assertTrue(VerifiedEngineReplacement.recover(installed,pinnedHash(root),{it.writeText("pinned-engine")})!!.usedPinnedFallback)
        assertEquals("old-engine",external.readText())
    }
    @Test fun symlinkMarkerUsesPinnedFallbackWithoutChangingTarget() = fixture { root, installed, _, _ ->
        val external=File(root,"external-marker").apply { writeText("private") }
        Files.createSymbolicLink(VerifiedEngineReplacement.pendingMarker(installed).toPath(),external.toPath())
        assertTrue(VerifiedEngineReplacement.recover(installed,pinnedHash(root),{it.writeText("pinned-engine")})!!.usedPinnedFallback)
        assertEquals("private",external.readText())
    }
    @Test fun symlinkEngineIsRejectedWithoutChangingTargetOrJournal() = fixture { root, installed, _, retained ->
        journal(installed,retained);installed.delete()
        val external=File(root,"external-engine").apply { writeText("private") }
        Files.createSymbolicLink(installed.toPath(),external.toPath())
        assertTrue(runCatching { VerifiedEngineReplacement.recover(installed,pinnedHash(root),{fail()}) }.isFailure)
        assertEquals("private",external.readText());assertTrue(VerifiedEngineReplacement.pendingMarker(installed).exists())
    }
}
