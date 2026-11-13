package com.yausername.youtubedl_android

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.nio.file.Files

/** Same-directory atomic replacement. Candidate checks never mutate the installed engine. */
internal object VerifiedEngineReplacement {
    fun digest(file: File): String = file.inputStream().use { input ->
        val hash = MessageDigest.getInstance("SHA-256")
        val bytes = ByteArray(65536)
        while (true) {
            val size = input.read(bytes)
            if (size < 0) break
            hash.update(bytes, 0, size)
        }
        hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun pendingMarker(installed: File): File = File(installed.parentFile, "yt-dlp.update-incomplete")

    fun requireNoIncompleteUpdate(installed: File) {
        val marker = pendingMarker(installed)
        check(!marker.exists() && marker.parentFile?.list()?.contains(marker.name) != true) { "Engine update is incomplete; retained rollback must be restored before execution" }
    }

    data class Recovery(val sha256: String, val usedPinnedFallback: Boolean)

    /** Restart recovery trusts no journal path: only an enumerated filename plus its own SHA-256. */
    fun recover(
        installed: File,
        pinnedSha256: String,
        pinnedAsset: (File) -> Unit,
        move: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
        removeMarker: (File) -> Boolean = { it.delete() },
    ): Recovery? {
        val marker = pendingMarker(installed)
        if (!marker.exists() && marker.parentFile?.list()?.contains(marker.name) != true) return null
        require(pinnedSha256.matches(Regex("[a-f0-9]{64}")))
        val parent = installed.parentFile!!.canonicalFile
        // A symlinked ancestor (Android 8 /data/user/0 -> /data/data) is a legitimate parent alias.
        val target = File(parent, installed.name)
        check(!Files.isSymbolicLink(target.toPath()) &&
            (target.isFile || target.parentFile?.list()?.contains(target.name) != true)) {
            "Recovery engine must not be a symlink or non-file"
        }
        val lines = if (!Files.isSymbolicLink(marker.toPath()) && marker.isFile && marker.length() < 1024) {
            marker.readLines()
        } else emptyList()
        val retainedName = lines.getOrNull(1).orEmpty()
        val backup = File(parent, retainedName)
        val retainedHash = retainedName.removePrefix("yt-dlp.rollback-")
        val validBackup = lines.size == 3 && lines[0] == "seali-engine-update-v1" &&
            lines[2].matches(Regex("[a-f0-9]{64}")) &&
            retainedName.matches(Regex("yt-dlp\\.rollback-[a-f0-9]{64}")) &&
            !Files.isSymbolicLink(backup.toPath()) && backup.isFile && digest(backup) == retainedHash
        val stage = File.createTempFile(".yt-dlp-recovery-", ".new", parent)
        try {
            val expected = if (validBackup) retainedHash else pinnedSha256
            if (validBackup) backup.inputStream().use { input ->
                FileOutputStream(stage).use { output -> input.copyTo(output); output.fd.sync() }
            } else {
                pinnedAsset(stage)
                FileOutputStream(stage, true).use { it.fd.sync() }
            }
            check(digest(stage) == expected) { "Recovery engine SHA-256 mismatch" }
            if (target.isFile) {
                val currentHash = digest(target)
                val prior = File(parent, "yt-dlp.rollback-$currentHash")
                val existingGood = !Files.isSymbolicLink(prior.toPath()) && prior.isFile && digest(prior) == currentHash
                if (!existingGood) {
                    // A corrupt/symlink collision must not block the independent pinned fallback.
                    // Retain the current bytes in a new owned file; never overwrite the collision.
                    val evidence = File.createTempFile("yt-dlp.recovery-evidence-$currentHash-", ".previous", parent)
                    target.inputStream().use { input ->
                        FileOutputStream(evidence).use { output -> input.copyTo(output); output.fd.sync() }
                    }
                    check(digest(evidence) == currentHash) { "Recovery evidence hash mismatch" }
                }
            }
            check(move(stage, target)) { "Cannot atomically restore engine" }
            check(digest(target) == expected) { "Restored engine SHA-256 mismatch" }
            check(removeMarker(marker)) { "Recovered engine but cannot clear update journal; retry recovery on restart" }
            return Recovery(expected, !validBackup)
        } finally { stage.delete() }
    }

    fun replace(
        installed: File,
        expectedSha256: String,
        download: (File) -> Unit,
        validate: (File) -> Unit,
        move: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
    ) {
        require(expectedSha256.matches(Regex("[a-f0-9]{64}"))) { "Release asset SHA-256 is required" }
        val parent = installed.parentFile!!.canonicalFile
        check(parent.isDirectory) { "Engine directory is missing" }
        // A symlinked ancestor (Android 8 /data/user/0 -> /data/data) is a legitimate parent alias.
        val target = File(parent, installed.name)
        check(!Files.isSymbolicLink(target.toPath())) { "Engine must not be a symlink" }
        requireNoIncompleteUpdate(target)
        val marker = pendingMarker(target)
        check(!Files.isSymbolicLink(marker.toPath())) { "Update marker must not be a symlink" }
        val staged = File.createTempFile(".yt-dlp-update-", ".new", parent)
        var backup: File? = null
        var swapped = false
        try {
            download(staged)
            FileOutputStream(staged, true).use { it.fd.sync() }
            check(digest(staged) == expectedSha256) { "Release asset SHA-256 mismatch" }
            validate(staged)
            if (target.exists()) {
                val oldHash = digest(target)
                val retained = File(parent, "yt-dlp.rollback-$oldHash")
                check(!Files.isSymbolicLink(retained.toPath())) { "Rollback must not be a symlink" }
                if (!retained.exists()) {
                    val tempBackup = File.createTempFile(".yt-dlp-backup-", ".new", parent)
                    try {
                        target.inputStream().use { input ->
                            FileOutputStream(tempBackup).use { output -> input.copyTo(output); output.fd.sync() }
                        }
                        check(digest(tempBackup) == oldHash) { "Engine rollback copy mismatch" }
                        check(move(tempBackup, retained)) { "Cannot retain engine rollback" }
                    } finally { tempBackup.delete() }
                }
                check(digest(retained) == oldHash) { "Retained engine rollback mismatch" }
                backup = retained
            }
            FileOutputStream(marker).use { output ->
                output.write(("seali-engine-update-v1\n" + (backup?.name ?: "none") + "\n" + expectedSha256 + "\n").toByteArray()); output.fd.sync()
            }
            check(move(staged, target)) { "Cannot atomically replace engine" }
            swapped = true
            check(digest(target) == expectedSha256) { "Installed engine SHA-256 mismatch" }
            validate(target)
            check(marker.delete()) { "Cannot complete engine update receipt" }
        } catch (failure: Exception) {
            if (swapped && backup != null) {
                val restore = File.createTempFile(".yt-dlp-restore-", ".new", parent)
                try {
                    backup.inputStream().use { input ->
                        FileOutputStream(restore).use { output -> input.copyTo(output); output.fd.sync() }
                    }
                    check(digest(restore) == digest(backup)) { "Rollback restoration hash mismatch" }
                    check(move(restore, target)) { "Engine rollback restore failed; retained copy: ${backup.name}" }
                    check(digest(target) == digest(backup)) { "Restored engine hash mismatch" }
                    marker.delete()
                } catch (rollbackFailure: Exception) {
                    failure.addSuppressed(rollbackFailure)
                } finally { restore.delete() }
            } else if (swapped) {
                if (target.delete()) marker.delete()
            } else if (!swapped) {
                marker.delete()
            }
            throw failure
        } finally { staged.delete() }
    }
}
