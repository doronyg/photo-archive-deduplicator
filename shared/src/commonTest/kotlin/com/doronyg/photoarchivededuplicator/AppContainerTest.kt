package com.doronyg.photoarchivededuplicator

import app.cash.sqldelight.db.SqlDriver
import com.doronyg.photoarchivededuplicator.scan.InventoryScanner
import com.doronyg.photoarchivededuplicator.scan.ScanEvent
import com.doronyg.photoarchivededuplicator.scan.TrackingIoDispatcher
import com.doronyg.photoarchivededuplicator.scan.createInMemoryDriver
import com.doronyg.photoarchivededuplicator.scan.DirectoryListing
import com.doronyg.photoarchivededuplicator.scan.SuspendingQueryDriver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class AppContainerTest {
    @Test
    fun shutdownDrainsExternalRepositoryTransactionBeforeClosingDriver() = runTest {
        val worker = StandardTestDispatcher(testScheduler)
        lateinit var driver: SuspendingQueryDriver
        var closed = false
        val container = createAppContainer(worker, worker, openDriver = {
            driver = SuspendingQueryDriver(createInMemoryDriver())
            object : SqlDriver by driver {
                override fun close() {
                    assertEquals(null, driver.currentTransaction())
                    closed = true
                    driver.close()
                }
            }
        }, createScanner = { _, _ ->
            object : InventoryScanner {
                override suspend fun startScan(root: Path, onEvent: suspend (ScanEvent) -> Unit) = Unit
                override suspend fun resume(onEvent: suspend (ScanEvent) -> Unit) = Unit
            }
        })
        val root = "/archive".toPath()
        container.repository.seedRoot(root)
        driver.suspendNextChildQuery = true
        val externalQuery = async {
            container.repository.recordListing(root, DirectoryListing(emptyList(), emptyList()), 1)
        }
        driver.entered.await()
        val shutdown = async { container.shutdown() }
        runCurrent()
        assertFalse(closed)
        driver.release.complete(Unit)
        externalQuery.await()
        shutdown.await()
        assertTrue(closed)
    }

    @Test
    fun startupAndShutdownUseIoAndConcurrentShutdownWaitsForScanCleanup() = runTest {
        val worker = StandardTestDispatcher(testScheduler)
        val io = TrackingIoDispatcher(worker)
        val ui = TrackingIoDispatcher(worker)
        val entered = CompletableDeferred<Unit>()
        var cleaned = false
        var closes = 0
        val scanner = object : InventoryScanner {
            override suspend fun startScan(root: Path, onEvent: suspend (ScanEvent) -> Unit) {
                try {
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        delay(100)
                        cleaned = true
                    }
                }
            }
            override suspend fun resume(onEvent: suspend (ScanEvent) -> Unit) = startScan("/archive".toPath(), onEvent)
        }
        val container = withContext(ui) { createAppContainer(io, worker, openDriver = {
            io.assertOnIo("open driver and initialize schema")
            assertFalse(ui.isRunningTask)
            val driver = createInMemoryDriver()
            object : SqlDriver by driver {
                override fun close() {
                    io.assertOnIo("close driver")
                    assertFalse(ui.isRunningTask)
                    assertTrue(cleaned, "Closing must wait for cancelled scan cleanup")
                    closes++
                    driver.close()
                }
            }
        }, createScanner = { _, _ -> scanner }) }
        container.scanController.start("/archive".toPath())
        entered.await()
        val first = async(ui) { container.shutdown() }
        val second = async(ui) { container.shutdown() }
        advanceUntilIdle()
        first.await()
        second.await()
        assertEquals(1, closes)
    }

    @Test
    fun cancellationDuringStartupDoesNotLeakOpenedDriver() = runTest {
        val worker = StandardTestDispatcher(testScheduler)
        val io = TrackingIoDispatcher(worker)
        var closes = 0
        lateinit var initializing: Job
        initializing = launch {
            createAppContainer(io, worker, openDriver = {
                io.assertOnIo("open driver")
                val driver = createInMemoryDriver()
                initializing.cancel() // Win the return-to-caller cancellation race.
                object : SqlDriver by driver {
                    override fun close() {
                        io.assertOnIo("close cancelled startup")
                        closes++
                        driver.close()
                    }
                }
            }, createScanner = { _, _ ->
                object : InventoryScanner {
                    override suspend fun startScan(root: Path, onEvent: suspend (ScanEvent) -> Unit) = Unit
                    override suspend fun resume(onEvent: suspend (ScanEvent) -> Unit) = Unit
                }
            })
        }
        advanceUntilIdle()
        initializing.join()
        assertEquals(1, closes)
    }
}
