package com.yausername.youtubedl_android

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

/** Replaces the packaged Python directory only, retaining the complete prior directory. */
internal object VerifiedPythonPayload {
    private const val MARKER = ".seali-python-archive-sha256"

    fun install(
        archive: File,
        destination: File,
        extractAndVerify: (File, File) -> Unit,
        move: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
    ): Boolean {
        val expected = VerifiedEngineReplacement.digest(archive)
        val parent = destination.parentFile!!.canonicalFile
        check(parent.isDirectory || parent.mkdirs()) { "Cannot prepare Python parent" }
        // A symlinked ancestor (Android 8 /data/user/0 -> /data/data) is a legitimate parent alias.
        // Only a symlinked leaf is rejected, and Files.isSymbolicLink also catches dangling links.
        val target = File(parent, destination.name)
        check(!Files.isSymbolicLink(target.toPath())) { "Python directory must not be a symlink" }
        val marker = File(target, MARKER)
        check(!Files.isSymbolicLink(marker.toPath())) { "Python marker must not be a symlink" }
        if (target.isDirectory && marker.isFile && marker.readText() == expected) return false
        val stage = File.createTempFile(".python-staged-", ".new", parent)
        check(stage.delete() && stage.mkdir())
        var retained: File? = null
        var installed = false
        try {
            extractAndVerify(archive, stage)
            val stagedMarker = File(stage, MARKER)
            check(!stagedMarker.exists() && !Files.isSymbolicLink(stagedMarker.toPath()))
            FileOutputStream(stagedMarker).use { output -> output.write(expected.toByteArray()); output.fd.sync() }
            if (target.exists()) {
                val old = File.createTempFile("python-retained-", ".previous", parent)
                check(old.delete())
                check(move(target, old)) { "Cannot retain previous Python directory" }
                retained = old
            }
            check(move(stage, target)) { "Cannot install verified Python directory" }
            installed = true
            return true
        } finally {
            if (!installed && retained != null && !target.exists()) {
                check(move(retained, target)) { "Python restoration failed; previous directory retained at ${retained.name}" }
            }
            // Retain failed staging too. Never recurse through an unqualified archive's symlinks.
        }
    }
}
