package com.doronyg.photoarchivededuplicator

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnosticsConcurrencyTest {
    private fun threadedTest(block: suspend CoroutineScope.() -> Unit) =
        runBlocking { withTimeout(10_000, block) }

    private fun TestCoroutineScheduler.publish() {
        advanceTimeBy(201)
        runCurrent()
    }

    private suspend fun DiagnosticLogger.drain(scheduler: TestCoroutineScheduler) = coroutineScope {
        val stopping = async(start = CoroutineStart.UNDISPATCHED) { shutdown() }
        scheduler.advanceUntilIdle()
        stopping.await()
    }

    @Test
    fun nativeLoggingContinuesWhileFileWriterIsBlocked() = threadedTest {
        val scheduler = TestCoroutineScheduler()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        val logger = DiagnosticLogger(dispatcher = StandardTestDispatcher(scheduler), sink = {
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
            scheduler.publish()
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            logger.i("Test", "console still works")
            scheduler.publish()
            assertTrue(delivered.await(3, TimeUnit.SECONDS), "A blocked file sink must not block native logs")
        } finally {
            release.countDown()
            logger.drain(scheduler)
        }
    }

    @Test
    fun persistentSinkFlushesOnIndependentThreadWhileFilesystemLaneIsBlocked() = threadedTest {
        val scheduler = TestCoroutineScheduler()
        val directory = File("build/test-log-fixtures/${UUID.randomUUID()}")
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val written = CountDownLatch(1)
        val fileClosed = CountDownLatch(1)
        val filesystem = IoLane.dedicated("inventory-filesystem-test")
        val file = RotatingLogFile({ directory })
        val logger = DiagnosticLogger(dispatcher = StandardTestDispatcher(scheduler), sink = {}, fileSink = object : DiagnosticFileSink {
            override fun write(entry: LogEntry) {
                assertTrue(Thread.currentThread().name.startsWith("diagnostic-file"))
                file.write(entry)
                written.countDown()
            }
            override fun close() {
                file.close()
                fileClosed.countDown()
            }
        })
        val listing = async {
            filesystem.run("blocked scan") {
                entered.complete(Unit)
                check(release.await(10, TimeUnit.SECONDS))
            }
        }
        try {
            entered.await()
            logger.i("Scan", "filesystem is still blocked")
            scheduler.publish()
            assertTrue(written.await(3, TimeUnit.SECONDS))
            assertTrue(File(directory, "diagnostics.log").readText().contains("filesystem is still blocked"))
        } finally {
            release.countDown()
            listing.await()
            filesystem.close {}
            logger.drain(scheduler)
            assertTrue(fileClosed.await(3, TimeUnit.SECONDS))
            directory.deleteRecursively()
        }
    }

    @Test
    fun stalledFileWriterHasBoundedLossWithoutBlockingProducersOrClose() = threadedTest {
        val scheduler = TestCoroutineScheduler()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val logger = DiagnosticLogger(dispatcher = StandardTestDispatcher(scheduler), capacity = 4, sink = {}, fileSink = object : DiagnosticFileSink {
            override fun write(entry: LogEntry) {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
            override fun close() { closed.countDown() }
        })
        try {
            logger.i("Test", "stall disk")
            scheduler.publish()
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            withTimeout(3_000) {
                coroutineScope {
                    repeat(8) { producer ->
                        launch(Dispatchers.Default) {
                            repeat(2000) { logger.d("Concurrent", "$producer:$it") }
                        }
                    }
                }
                scheduler.publish()
                repeat(4) { logger.d("Test", "overflow disk queue $it") }
                scheduler.publish()
                assertTrue(logger.state.value.fileDropped > 0)
            }
            withTimeout(1_000) { logger.shutdown(timeoutMillis = 100) }
        } finally {
            release.countDown()
            logger.close()
            assertTrue(closed.await(3, TimeUnit.SECONDS))
        }
    }

    @Test
    fun filesKeepFlushedTailWhileConsoleIsStalledAndProducersFlood() = threadedTest {
        val scheduler = TestCoroutineScheduler()
        val directory = File("build/test-log-fixtures/${UUID.randomUUID()}")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val callerThread = Thread.currentThread().id
        val writerThread = AtomicLong(callerThread)
        val fileClosed = CountDownLatch(1)
        val flushed = Channel<LogEntry>(Channel.UNLIMITED)
        val file = RotatingLogFile({
            writerThread.set(Thread.currentThread().id)
            directory
        })
        val logger = DiagnosticLogger(dispatcher = StandardTestDispatcher(scheduler), capacity = 32, retained = 16, sink = {
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
        }, fileSink = object : DiagnosticFileSink {
            override fun write(entry: LogEntry) {
                file.write(entry)
                flushed.trySend(entry)
            }
            override fun close() {
                file.close()
                fileClosed.countDown()
            }
        })
        try {
            logger.d("Scan", "BEGIN first operation")
            scheduler.publish()
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertEquals("BEGIN first operation", flushed.receive().message)
            withTimeout(3_000) {
                coroutineScope {
                    repeat(8) { producer ->
                        launch(Dispatchers.Default) {
                            repeat(2000) { logger.d("Metadata", "$producer:$it") }
                        }
                    }
                }
            }
            scheduler.publish()
            repeat(32) { logger.d("Metadata", "overflow console $it") }
            scheduler.publish()
            assertTrue(logger.state.value.sinkDropped > 0)
            logger.blockingOperation("Scan", "latest operation") { }
            scheduler.publish()
            withTimeout(3_000) {
                while (!flushed.receive().message.startsWith("END latest operation")) {}
            }
            val text = File(directory, "diagnostics.log").readText()
            assertTrue(text.contains("BEGIN first operation"))
            assertTrue(text.contains("BEGIN latest operation"))
            assertTrue(text.contains("END latest operation"))
            assertTrue(writerThread.get() != callerThread)
            assertTrue(logger.state.value.dropped > 0)
        } finally {
            release.countDown()
            logger.drain(scheduler)
            assertTrue(fileClosed.await(3, TimeUnit.SECONDS))
            directory.deleteRecursively()
        }
    }

    @Test
    fun concurrentProducersFinishWhileNativeSinkIsStalled() = threadedTest {
        val scheduler = TestCoroutineScheduler()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val logger = DiagnosticLogger(dispatcher = StandardTestDispatcher(scheduler), capacity = 32, retained = 16, sink = {
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
        })
        try {
            logger.i("Test", "stall sink")
            scheduler.publish()
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
            scheduler.publish()
            repeat(32) { logger.d("Test", "overflow sink $it") }
            scheduler.publish()
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
