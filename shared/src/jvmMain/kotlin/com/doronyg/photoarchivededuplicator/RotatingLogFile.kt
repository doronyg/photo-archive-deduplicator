package com.doronyg.photoarchivededuplicator

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Single-worker sink. Construction does not resolve paths, open files or emit diagnostics. */
internal class RotatingLogFile(
    private val directory: () -> File,
    private val maxBytes: Long = 5L * 1024 * 1024,
    private val fileCount: Int = 5,
) : DiagnosticFileSink, Closeable {
    private var output: BufferedOutputStream? = null
    private var size = 0L

    init {
        require(maxBytes in 2..Int.MAX_VALUE.toLong() && fileCount > 0)
    }

    override fun write(entry: LogEntry) {
        val bytes = boundedLine(entry)
        try {
            val folder = directory()
            val current = File(folder, "diagnostics.log")
            if (output == null) {
                Files.createDirectories(folder.toPath())
                size = if (current.exists()) current.length() else 0
                output = BufferedOutputStream(FileOutputStream(current, true))
            }
            if (size + bytes.size > maxBytes) {
                close()
                if (fileCount == 1) {
                    Files.deleteIfExists(current.toPath())
                } else {
                    Files.deleteIfExists(File(folder, "diagnostics.${fileCount - 1}.log").toPath())
                    for (index in fileCount - 2 downTo 1) {
                        val source = File(folder, "diagnostics.$index.log").toPath()
                        if (Files.exists(source)) Files.move(source, File(folder, "diagnostics.${index + 1}.log").toPath(), REPLACE_EXISTING)
                    }
                    Files.move(current.toPath(), File(folder, "diagnostics.1.log").toPath(), REPLACE_EXISTING)
                }
                output = BufferedOutputStream(FileOutputStream(current, true))
                size = 0
            }
            output!!.write(bytes)
            // Flush Java buffers per entry, not fsync: queued events and OS caches can still be lost.
            output!!.flush()
            size += bytes.size
        } catch (e: Exception) {
            try { close() } catch (_: Exception) { }
            throw e
        }
    }

    private fun boundedLine(entry: LogEntry): ByteArray {
        val bytes = (formatLog(entry) + "\n").toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return bytes
        var end = maxBytes.toInt() - 1
        while (end > 0 && bytes[end].toInt() and 0xc0 == 0x80) end--
        return bytes.copyOf(end + 1).also { it[end] = '\n'.code.toByte() }
    }

    override fun close() {
        val stream = output
        output = null
        stream?.close()
    }
}
