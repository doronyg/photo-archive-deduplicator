package com.doronyg.photoarchivededuplicator

import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RotatingLogFileTest {
    private fun withDirectory(test: (File) -> Unit) {
        val directory = File("build/test-log-fixtures/${UUID.randomUUID()}")
        directory.mkdirs()
        try { test(directory) } finally { directory.deleteRecursively() }
    }

    @Test
    fun appendsAcrossRestartAndFlushesBeforeClose() = withDirectory { directory ->
        val first = RotatingLogFile({ directory })
        first.write(entry("first session 日本語"))
        assertTrue(File(directory, "diagnostics.log").readText().contains("first session 日本語"))
        first.close()
        RotatingLogFile({ directory }).use { second ->
            second.write(entry("second session"))
            val text = File(directory, "diagnostics.log").readText()
            assertTrue(text.contains("first session 日本語"))
            assertTrue(text.contains("second session"))
        }
    }

    @Test
    fun rotationBoundsUtf8BytesAndRetainsLatestFiles() = withDirectory { directory ->
        RotatingLogFile({ directory }, maxBytes = 160, fileCount = 3).use { writer ->
            repeat(20) { writer.write(entry("event-$it ${"界".repeat(12)}")) }
            val files = directory.listFiles()!!.sortedBy { it.name }
            assertEquals(3, files.size)
            assertTrue(files.all { it.length() <= 160 })
            assertTrue(File(directory, "diagnostics.log").readText().contains("event-19"))
            assertTrue(files.none { it.readText().contains("event-0 ") })
        }
        RotatingLogFile({ directory }, maxBytes = 160, fileCount = 3).use { writer ->
            writer.write(entry("event-20 ${"界".repeat(12)}"))
            assertEquals(3, directory.listFiles()!!.size)
            assertTrue(File(directory, "diagnostics.1.log").readText().contains("event-19"))
            assertTrue(File(directory, "diagnostics.log").readText().contains("event-20"))
        }
    }

    @Test
    fun oversizedUnicodeEntryAndSingleFileRetentionStayBounded() = withDirectory { directory ->
        RotatingLogFile({ directory }, maxBytes = 64, fileCount = 1).use { writer ->
            writer.write(entry("界".repeat(100)))
            val current = File(directory, "diagnostics.log")
            assertTrue(current.length() <= 64)
            assertTrue(!current.readText().contains('\uFFFD'))
            writer.write(entry("latest"))
            assertEquals(1, directory.listFiles()!!.size)
            assertTrue(current.readText().contains("latest"))
            assertTrue(!current.readText().contains("界"))
        }
    }

    @Test
    fun directoryAndRotationFailuresPropagateAndCanRecover() = withDirectory { directory ->
        val blocked = File(directory, "logs").apply { writeText("not a directory") }
        val writer = RotatingLogFile({ blocked }, maxBytes = 100, fileCount = 2)
        assertFailsWith<Exception> { writer.write(entry("failure")) }
        blocked.delete()
        writer.write(entry("recovered"))
        File(blocked, "diagnostics.1.log").mkdirs()
        File(blocked, "diagnostics.1.log/obstacle").writeText("blocks replacement")
        assertFailsWith<Exception> { writer.write(entry("x".repeat(60))) }
        File(blocked, "diagnostics.1.log").deleteRecursively()
        writer.write(entry("after rotation"))
        assertTrue(File(blocked, "diagnostics.log").readText().contains("after rotation"))
        writer.close()
    }

    private fun entry(message: String) = LogEntry(1, 123, LogLevel.I, "Test", "worker", message)
}
