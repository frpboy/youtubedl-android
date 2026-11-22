package com.yausername.youtubedl_android

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock

/**
 * One short mutual-exclusion guard for every writer of the engine layout (the yt-dlp file, its plugin
 * folder, update swaps, recovery, first install) and for the launch snapshot copy. It is held for file
 * work only, never across a download or a running child. Lock order for callers that also own an app
 * lock: app lock first, then this guard, and nothing acquires an app lock while holding it.
 */
object EngineGuard {
    private val lock = ReentrantLock()
    fun <T> exclusive(block: () -> T): T {
        lock.lock()
        try { return block() } finally { lock.unlock() }
    }
}

/** The bytes a launch must run: the engine digest and every plugin file (relative path to digest), nothing else. */
class EngineExpectation(val engineSha256: String, val pluginFiles: Map<String, String>)

/** The installed engine or plugin files differ from what the caller approved. Nothing was started. */
class EngineChangedException(message: String) : YoutubeDLException(message)

/**
 * Cancels one launch even before it has registered or started. Pass it to [YoutubeDL.execute]. A cancel that
 * arrives early is remembered, so the launch stops before it starts a process. A cancel that arrives
 * later destroys the process the launch owns.
 */
class LaunchCancellation {
    private var cancelled = false
    private var target: OwnedInvocation? = null

    @Synchronized fun cancel() {
        cancelled = true
        target?.cancel()
    }
    @Synchronized fun isCancelled(): Boolean = cancelled
    @Synchronized internal fun bind(invocation: OwnedInvocation) {
        check(target == null) { "Cancellation is already bound to a launch" }
        target = invocation
        if (cancelled) invocation.cancel()
    }
}

private object DiscardingOutput : java.io.OutputStream() {
    override fun write(b: Int) {}
    override fun write(b: ByteArray, off: Int, len: Int) {}
}

internal class EngineSnapshot(val engine: File, val engineSha256: String, val pluginFiles: Map<String, String>) {
    fun matches(expected: EngineExpectation) = engineSha256 == expected.engineSha256 && pluginFiles == expected.pluginFiles
}

/**
 * Python opens the engine archive and the plugin files again after the process has started, so replacing
 * them in place while a child runs changes what that child imports (measured: a running child fails with a
 * bad zip header when the engine path is swapped). A launch therefore runs from a private copy made, and
 * hashed while copying, under [EngineGuard]. yt-dlp finds plugins in a yt-dlp-plugins folder next to the
 * engine file it runs, so the same layout is copied beside the snapshot.
 */
internal object EngineSnapshots {
    const val ENGINE_NAME = "yt-dlp"
    const val PLUGINS = "yt-dlp-plugins"
    private const val MAX_DEPTH = 8
    private const val MAX_FILES = 64

    fun create(installedEngine: File, into: File): EngineSnapshot = scan(installedEngine, into)!!

    /** Same rules as [create], without copying: what a launch would run right now. */
    fun measure(installedEngine: File): EngineExpectation =
        scan(installedEngine, null)!!.let { EngineExpectation(it.engineSha256, it.pluginFiles) }

    private fun scan(installedEngine: File, into: File?): EngineSnapshot? {
        val source = File(installedEngine.parentFile!!.canonicalFile, installedEngine.name)
        check(!Files.isSymbolicLink(source.toPath()) && source.isFile) { "Engine must be a regular file" }
        val engine = File(into ?: source.parentFile, ENGINE_NAME)
        val engineSha = copyHashing(source, if (into == null) null else engine)
        val plugins = sortedMapOf<String, String>()
        val pluginSource = File(source.parentFile, PLUGINS)
        if (Files.isSymbolicLink(pluginSource.toPath())) throw YoutubeDLException("Plugin folder must not be a symlink")
        if (pluginSource.exists()) {
            check(pluginSource.isDirectory) { "Plugin folder is not a directory" }
            copyTree(pluginSource, into?.let { File(it, PLUGINS) }, "", plugins, 0)
        }
        return EngineSnapshot(engine, engineSha, plugins)
    }

    private fun copyTree(from: File, to: File?, prefix: String, out: MutableMap<String, String>, depth: Int) {
        check(depth <= MAX_DEPTH) { "Plugin folder is too deep" }
        if (to != null) check(to.isDirectory || to.mkdir()) { "Cannot create snapshot folder" }
        for (child in from.listFiles().orEmpty().sortedBy { it.name }) {
            check(!Files.isSymbolicLink(child.toPath())) { "Plugin folder must not contain symlinks" }
            val rel = prefix + child.name
            when {
                child.isDirectory ->
                    // Python's own bytecode cache is not part of the approved layout.
                    if (child.name != "__pycache__") copyTree(child, to?.let { File(it, child.name) }, "$rel/", out, depth + 1)
                child.isFile -> {
                    check(out.size < MAX_FILES) { "Too many plugin files" }
                    out[rel] = copyHashing(child, to?.let { File(it, child.name) })
                }
                else -> throw YoutubeDLException("Plugin folder holds an unexpected entry")
            }
        }
    }

    private fun copyHashing(from: File, to: File?): String {
        val hash = MessageDigest.getInstance("SHA-256")
        from.inputStream().use { input ->
            (if (to == null) DiscardingOutput else FileOutputStream(to)).use { output ->
                val buffer = ByteArray(65536)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    hash.update(buffer, 0, n)
                    output.write(buffer, 0, n)
                }
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    /** Removes only this layout inside an invocation directory. Links are removed, never followed. */
    fun remove(dir: File): Boolean {
        var ok = true
        fun delete(f: File) {
            if (!Files.isSymbolicLink(f.toPath()) && f.isDirectory) f.listFiles()?.forEach { delete(it) }
            if (!f.delete() && f.exists()) ok = false
        }
        for (name in listOf(ENGINE_NAME, PLUGINS)) {
            val f = File(dir, name)
            if (Files.isSymbolicLink(f.toPath()) || f.exists()) delete(f)
        }
        return ok
    }
}
