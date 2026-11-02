package com.doronyg.photoarchivededuplicator

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnosticsConcurrencyTest {
    @Test
    fun nativeLoggingContinuesWhileFileWriterIsBlocked() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        val logger = DiagnosticLogger(sink = {
            if (it.message == "console still works") delivered.countDown()
        }, fileSink = object : DiagnosticFileSink {
            override fun write(entry: LogEntry) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
            override fun close() {}
        })
        try {
            logger.i("Test", "block file writer")
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            logger.i("Test", "console still works")
            assertTrue(delivered.await(3, TimeUnit.SECONDS), "A blocked file sink must not block native logs")
        } finally {
            release.countDown()
            logger.shutdown()
        }
    }

    @Test
    fun persistentSinkFlushesOnIndependentThreadWhileFilesystemLaneIsBlocked() = runBlocking {
        val directory = File("build/test-log-fixtures/${UUID.randomUUID()}")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val written = CountDownLatch(1)
        val filesystem = IoLane.dedicated("inventory-filesystem-test")
        val file = RotatingLogFile({ directory })
        val logger = DiagnosticLogger(sink = {}, fileSink = object : DiagnosticFileSink {
            override fun write(entry: LogEntry) {
                assertTrue(Thread.currentThread().name.startsWith("diagnostic-file"))
                file.write(entry)
                written.countDown()
            }
            override fun close() = file.close()
        })
        val listing = async {
            filesystem.run("blocked scan") {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        }
        try {
            // Suspend the caller so the scan coroutine can dispatch before waiting.
            withTimeout(3000) {
                while (entered.count != 0L) delay(5)
            }
            logger.i("Scan", "filesystem is still blocked")
            assertTrue(written.await(3, TimeUnit.SECONDS))
            assertTrue(File(directory, "diagnostics.log").readText().contains("filesystem is still blocked"))
        } finally {
            release.countDown()
            listing.await()
            filesystem.close {}
            logger.shutdown()
            directory.deleteRecursively()
        }
    }

    @Test
    fun stalledFileWriterHasBoundedLossWithoutBlockingProducersOrClose() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val logger = DiagnosticLogger(capacity = 4, sink = {}, fileSink = object : DiagnosticFileSink {
            override fun write(entry: LogEntry) {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
            override fun close() { closed.countDown() }
        })
        try {
            logger.i("Test", "stall disk")
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            withTimeout(3_000) {
                coroutineScope {
                    repeat(8) { producer ->
                        launch(Dispatchers.Default) {
                            repeat(2000) { logger.d("Concurrent", "$producer:$it") }
                        }
                    }
                }
                while (logger.state.value.fileDropped == 0L) {
                    logger.d("Test", "overflow disk queue")
                    delay(20)
                }
            }
            withTimeout(1_000) { logger.shutdown(timeoutMillis = 100) }
        } finally {
            release.countDown()
            logger.close()
            assertTrue(closed.await(3, TimeUnit.SECONDS))
        }
    }

    @Test
    fun filesKeepFlushedTailWhileConsoleIsStalledAndProducersFlood() = runBlocking {
        val directory = File("build/test-log-fixtures/${UUID.randomUUID()}")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val callerThread = Thread.currentThread().id
        val writerThread = AtomicLong(callerThread)
        val logger = DiagnosticLogger(capacity = 32, retained = 16, sink = {
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
        }, fileSink = RotatingLogFile({
            writerThread.set(Thread.currentThread().id)
            directory
        }))
        try {
            logger.d("Scan", "BEGIN first operation")
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            withTimeout(3_000) {
                coroutineScope {
                    repeat(8) { producer ->
                        launch(Dispatchers.Default) {
                            repeat(2000) { logger.d("Metadata", "$producer:$it") }
                        }
                    }
                    while (logger.state.value.sinkDropped == 0L) {
                        logger.d("Metadata", "overflow console")
                        delay(20)
                    }
                }
            }
            logger.blockingOperation("Scan", "latest operation") { }
            withTimeout(3_000) {
                while (true) {
                    val text = File(directory, "diagnostics.log").readText()
                    if (text.contains("END latest operation") && logger.state.value.sinkDropped > 0) {
                        assertTrue(text.contains("BEGIN first operation"))
                        assertTrue(text.contains("BEGIN latest operation"))
                        break
                    }
                    delay(20)
                }
            }
            assertTrue(writerThread.get() != callerThread)
            assertTrue(logger.state.value.dropped > 0)
        } finally {
            release.countDown()
            logger.shutdown()
            directory.deleteRecursively()
        }
    }

    @Test
    fun concurrentProducersFinishWhileNativeSinkIsStalled() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val logger = DiagnosticLogger(capacity = 32, retained = 16, sink = {
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
        })
        try {
            logger.i("Test", "stall sink")
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            withTimeout(3_000) {
                coroutineScope {
                    repeat(8) { producer ->
                        launch(Dispatchers.Default) {
                            repeat(2000) { logger.d("Concurrent", "$producer:$it") }
                        }
                    }
                }
            }
            withTimeout(3_000) {
                while (logger.state.value.dropped == 0L || logger.state.value.sinkDropped == 0L) {
                    logger.d("Test", "overflow sink")
                    delay(20)
                }
            }
            val state = logger.state.value
            assertTrue(state.entries.size <= 16)
            assertEquals(state.entries.map { it.id }.sorted().distinct(), state.entries.map { it.id })
            assertTrue(state.entries.all { it.thread.startsWith("JVM/") })
        } finally {
            release.countDown()
            logger.close()
        }
    }
}
