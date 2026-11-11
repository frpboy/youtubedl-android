package com.yausername.youtubedl_android

import java.io.File
import java.io.FileOutputStream

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
        check(destination.absoluteFile == destination.canonicalFile) { "Python directory must not be a symlink" }
        val marker = File(destination, MARKER)
        check(marker.absoluteFile == marker.canonicalFile) { "Python marker must not be a symlink" }
        if (destination.isDirectory && marker.isFile && marker.readText() == expected) return false
        val stage = File.createTempFile(".python-staged-", ".new", parent)
        check(stage.delete() && stage.mkdir())
        var retained: File? = null
        var installed = false
        try {
            extractAndVerify(archive, stage)
            val stagedMarker = File(stage, MARKER)
            check(!stagedMarker.exists() && stagedMarker.absoluteFile == stagedMarker.canonicalFile)
            FileOutputStream(stagedMarker).use { output -> output.write(expected.toByteArray()); output.fd.sync() }
            if (destination.exists()) {
                val old = File.createTempFile("python-retained-", ".previous", parent)
                check(old.delete())
                check(move(destination, old)) { "Cannot retain previous Python directory" }
                retained = old
            }
            check(move(stage, destination)) { "Cannot install verified Python directory" }
            installed = true
            return true
        } finally {
            if (!installed && retained != null && !destination.exists()) {
                check(move(retained, destination)) { "Python restoration failed; previous directory retained at ${retained.name}" }
            }
            // Retain failed staging too. Never recurse through an unqualified archive's symlinks.
        }
    }
}
