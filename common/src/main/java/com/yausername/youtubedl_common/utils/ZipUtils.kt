package com.yausername.youtubedl_common.utils

import android.system.Os
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.io.IOUtils
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

object ZipUtils {
    /** Exact archive-to-extraction content check, including retained internal symlink text. */
    fun unzipAndVerify(sourceFile: File, targetDirectory: File) {
        unzip(sourceFile, targetDirectory)
        fun digest(input: InputStream): ByteArray = input.use {
            val hash = java.security.MessageDigest.getInstance("SHA-256")
            val bytes = ByteArray(65536)
            while (true) {
                val n = it.read(bytes)
                if (n < 0) break
                hash.update(bytes, 0, n)
            }
            hash.digest()
        }
        ZipFile(sourceFile).use { archive ->
            val entries = archive.entries
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val output = File(targetDirectory, entry.name)
                check(output.canonicalPath.startsWith(targetDirectory.canonicalPath + File.separator)) {
                    "Extracted Python entry escapes its directory"
                }
                if (entry.isDirectory) check(output.isDirectory)
                else if (entry.isUnixSymlink) {
                    val expected = archive.getInputStream(entry).use { String(it.readBytes(), StandardCharsets.UTF_8) }
                    check(Os.readlink(output.absolutePath) == expected) { "Python symlink mismatch" }
                } else {
                    check(output.isFile && output.length() == entry.size) { "Python entry missing or wrong size" }
                    check(digest(archive.getInputStream(entry)).contentEquals(digest(output.inputStream()))) {
                        "Python extracted entry SHA-256 mismatch"
                    }
                }
            }
        }
    }

    fun unzip(sourceFile: File?, targetDirectory: File) {
        ZipFile(sourceFile).use { zipFile ->
            val entries = zipFile.entries
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val entryDestination = File(targetDirectory, entry.name)
                // prevent zipSlip
                if (!entryDestination.canonicalPath.startsWith(targetDirectory.canonicalPath + File.separator)) {
                    throw IllegalAccessException("Entry is outside of the target dir: " + entry.name)
                }
                if (entry.isDirectory) {
                    entryDestination.mkdirs()
                } else if (entry.isUnixSymlink) {
                    zipFile.getInputStream(entry).use { `in` ->
                        val symlink = IOUtils.toString(`in`, StandardCharsets.UTF_8)
                        Os.symlink(symlink, entryDestination.absolutePath)
                    }
                } else {
                    entryDestination.parentFile?.mkdirs()
                    zipFile.getInputStream(entry).use { `in` ->
                        FileOutputStream(entryDestination).use { out ->
                            IOUtils.copy(
                                `in`,
                                out
                            )
                        }
                    }
                }
            }
        }
    }

    fun unzip(inputStream: InputStream?, targetDirectory: File) {
        ZipArchiveInputStream(BufferedInputStream(inputStream)).use { zis ->
            var entry: ZipArchiveEntry? = null
            while (zis.nextZipEntry.also { entry = it } != null) {
                val entryDestination = File(targetDirectory, entry!!.name)
                // prevent zipSlip
                if (!entryDestination.canonicalPath.startsWith(targetDirectory.canonicalPath + File.separator)) {
                    throw IllegalAccessException("Entry is outside of the target dir: " + entry!!.name)
                }
                if (entry!!.isDirectory) {
                    entryDestination.mkdirs()
                } else {
                    entryDestination.parentFile.mkdirs()
                    FileOutputStream(entryDestination).use { out -> IOUtils.copy(zis, out) }
                }
            }
        }
    }
}