package com.yausername.youtubedl_android

import java.io.File
import java.io.IOException
import java.util.UUID

/** One reserved task owns one Process and one private temporary directory. No PID/shell use. */
internal class OwnedInvocation private constructor(val directory: File) {
    private var process: Process? = null
    private var cancelled = false

    @Synchronized fun attach(started: Process) {
        check(process == null)
        process = started
        if (cancelled) started.destroy()
    }
    @Synchronized fun cancel(): Boolean {
        if (cancelled) return false
        cancelled = true
        process?.destroy()
        return true
    }
    @Synchronized fun wasCancelled(): Boolean = cancelled

    /** Bounded parent reaping; restricted EJS child has separate parent-death fencing. */
    fun stopAndWait(): Boolean {
        val p = synchronized(this) { process } ?: return true
        p.destroy()
        repeat(100) {
            try { p.exitValue(); return true } catch (_: IllegalThreadStateException) { }
            try { Thread.sleep(20) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt(); return false
            }
        }
        return false
    }
    /** Never remove a directory while our Python process may still be running. */
    fun cleanAfterExit(): Boolean {
        val p = synchronized(this) { process }
        if (p != null) try { p.exitValue() } catch (_: IllegalThreadStateException) { return false }
        // No recursive traversal, links, user paths or cache-wide deletion.
        val parent = directory.canonicalFile
        var clean = true
        for (f in directory.listFiles() ?: emptyArray()) {
            if (f.canonicalFile.parentFile != parent || f.canonicalFile != f.absoluteFile ||
                !f.isFile || !f.name.endsWith(".js")) { clean = false; continue }
            if (!f.delete()) clean = false
        }
        return clean && directory.delete()
    }
    companion object {
        fun create(cacheDir: File): OwnedInvocation {
            // Context cache paths may have trusted aliased ancestors. Resolve that root once;
            // leaf comparisons below are relative to the canonical root, not Android path spelling.
            val parent = File(cacheDir.canonicalFile, "seali-owned-invocations")
            if (!parent.exists() && !parent.mkdir()) throw IOException("Private temporary parent unavailable")
            if (!parent.isDirectory || parent.canonicalFile != parent.absoluteFile) throw IOException("Unsafe temporary parent")
            val dir = File(parent, "inv-" + UUID.randomUUID().toString())
            if (!dir.mkdir()) throw IOException("Private invocation directory unavailable")
            val ok = dir.setReadable(false, false) && dir.setWritable(false, false) &&
                dir.setExecutable(false, false) && dir.setReadable(true, true) &&
                dir.setWritable(true, true) && dir.setExecutable(true, true)
            if (!ok) { dir.delete(); throw IOException("Private temporary permissions unavailable") }
            // No startup sweep: another process may own an older directory after an app crash.
            return OwnedInvocation(dir)
        }
    }
}
