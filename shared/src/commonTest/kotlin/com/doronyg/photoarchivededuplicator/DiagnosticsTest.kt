package com.doronyg.photoarchivededuplicator

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnosticsTest {
    @Test
    fun boundedQueueBatchesAndCountsOverflow() = runTest {
        val logger = DiagnosticLogger(StandardTestDispatcher(testScheduler), capacity = 2, retained = 2, sink = {})
        repeat(5) { logger.d("Test", "$it") }
        assertTrue(logger.state.value.entries.isEmpty())
        runCurrent()
        advanceTimeBy(201)
        runCurrent()
        assertEquals(3, logger.state.value.dropped)
        assertEquals(listOf("3", "4"), logger.state.value.entries.map { it.message })
        assertTrue(formatLogs(logger.state.value.entries).contains("D/Test"))
        logger.close()
    }

    @Test
    fun operationBeginsBeforeFailureAndCancellation() = runTest {
        val logger = DiagnosticLogger(StandardTestDispatcher(testScheduler), sink = {})
        try {
            logger.operation("IO", "read") {
                logger.i("Test", "inside")
                throw IllegalStateException("broken")
            }
        } catch (_: IllegalStateException) { }
        try {
            logger.operation("IO", "cancel") { throw CancellationException("stop") }
        } catch (_: CancellationException) { }
        runCurrent()
        advanceTimeBy(201)
        runCurrent()
        val messages = logger.state.value.entries.map { it.message }
        assertTrue(messages[0].startsWith("BEGIN read"))
        assertEquals("inside", messages[1])
        assertTrue(messages[2].contains("FAIL read"))
        assertTrue(messages.last().contains("CANCEL cancel"))
        logger.close()
    }

    @Test
    fun retentionClearAndPublicationAreBounded() = runTest {
        val logger = DiagnosticLogger(StandardTestDispatcher(testScheduler), retained = 2, sink = {})
        logger.i("Test", "first")
        runCurrent()
        advanceTimeBy(199)
        runCurrent()
        assertTrue(logger.state.value.entries.isEmpty())
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, logger.state.value.entries.size)
        logger.i("Test", "second")
        logger.i("Test", "third")
        assertEquals(1, logger.state.value.entries.size)
        advanceTimeBy(200)
        runCurrent()
        assertEquals(listOf(2L, 3L), logger.state.value.entries.map { it.id })
        logger.clear()
        advanceTimeBy(200)
        runCurrent()
        assertTrue(logger.state.value.entries.isEmpty())
        logger.close()
    }

    @Test
    fun sinkErrorsAreCountedWithoutRecursiveLogs() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val logger = DiagnosticLogger(dispatcher, sink = { error("sink unavailable") }, sinkDispatcher = dispatcher)
        logger.e("Test", "original")
        runCurrent()
        advanceTimeBy(401)
        runCurrent()
        assertEquals(1, logger.state.value.sinkFailures)
        assertEquals(1, logger.state.value.entries.size)
        logger.close()
    }

    @Test
    fun newestOperationBoundariesSurviveMetadataFlood() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val logger = DiagnosticLogger(dispatcher, capacity = 4, sink = {}, sinkDispatcher = dispatcher)
        repeat(10_000) { logger.d("Metadata", "file-$it") }
        logger.blockingOperation("Scan", "latest operation") { }
        advanceTimeBy(401)
        runCurrent()
        val snapshot = logger.state.value
        assertEquals(9998, snapshot.dropped)
        assertTrue(snapshot.entries.takeLast(2)[0].message.startsWith("BEGIN latest operation"))
        assertTrue(snapshot.entries.last().message.startsWith("END latest operation"))
        logger.close()
    }

    @Test
    fun fileErrorsAreVisibleAndRateLimitedWithoutRecursion() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var attempts = 0
        val logger = DiagnosticLogger(dispatcher, sink = {}, sinkDispatcher = dispatcher,
            fileSink = object : DiagnosticFileSink {
                override fun write(entry: LogEntry) { attempts++; error("disk unavailable") }
                override fun close() { }
            })
        repeat(10) { logger.i("Test", "$it") }
        advanceTimeBy(901)
        runCurrent()
        assertEquals(1, attempts)
        assertEquals(1, logger.state.value.fileFailures)
        assertTrue(logger.state.value.fileError!!.contains("disk unavailable"))
        assertEquals(10, logger.state.value.entries.size)
        logger.close()
    }

    @Test
    fun shutdownDrainsPendingEntriesAndClosesFileOnWorker() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val written = mutableListOf<String>()
        var closed = false
        val logger = DiagnosticLogger(dispatcher, sink = {}, sinkDispatcher = dispatcher,
            fileSink = object : DiagnosticFileSink {
                override fun write(entry: LogEntry) { written.add(entry.message) }
                override fun close() { closed = true }
            })
        logger.i("Lifecycle", "last entry")
        logger.shutdown()
        assertEquals(listOf("last entry"), written)
        assertTrue(closed)
    }
}
