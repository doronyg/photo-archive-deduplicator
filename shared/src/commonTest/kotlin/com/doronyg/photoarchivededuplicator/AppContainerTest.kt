package com.doronyg.photoarchivededuplicator

import app.cash.sqldelight.db.SqlDriver
import com.doronyg.photoarchivededuplicator.scan.InventoryScanner
import com.doronyg.photoarchivededuplicator.scan.ScanEvent
import com.doronyg.photoarchivededuplicator.scan.TrackingIoDispatcher
import com.doronyg.photoarchivededuplicator.scan.createInMemoryDriver
import com.doronyg.photoarchivededuplicator.scan.DirectoryListing
import com.doronyg.photoarchivededuplicator.scan.SuspendingQueryDriver
import com.doronyg.photoarchivededuplicator.scan.DirectoryRepository
import com.doronyg.photoarchivededuplicator.scan.ScanState
import com.doronyg.photoarchivededuplicator.exif.ExifSource
import com.doronyg.photoarchivededuplicator.exif.ExifMetadata
import com.doronyg.photoarchivededuplicator.exif.ExifReadResult
import com.doronyg.photoarchivededuplicator.exif.ExifStatus
import com.doronyg.photoarchivededuplicator.exif.ExifQueuePhase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
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
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class AppContainerTest {
    private suspend fun seedAmbiguousDatedGroup(repository: DirectoryRepository, root: Path) {
        for (copy in listOf("a", "b")) {
            val directory = root / copy / "2013_06_25"
            repository.seedRoot(directory)
            repository.recordListing(directory, DirectoryListing(emptyList(), listOf(
                DirectoryListing.Entry(directory / "DSC00413.ARW", 42, null),
            )), 1)
        }
    }

    @Test
    fun completedScanAutomaticallyScoresExtractsAndRescoresWithoutUiOrStartupReads() = runTest(timeout = 10.seconds) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val root = "/archive".toPath()
        val reads = mutableListOf<Path>()
        lateinit var repository: DirectoryRepository
        val container = createAppContainer(dispatcher, dispatcher, openDriver = { createInMemoryDriver() },
            createScanner = { repo, _ ->
                repository = repo
                object : InventoryScanner {
                    override suspend fun startScan(root: Path, onEvent: suspend (ScanEvent) -> Unit) {
                        seedAmbiguousDatedGroup(repo, root)
                    }
                    override suspend fun resume(onEvent: suspend (ScanEvent) -> Unit) = Unit
                }
            }, createExifSource = {
                ExifSource { file ->
                    assertTrue(repository.currentScanSession()!!.finished, "EXIF must follow the final scan checkpoint")
                    assertEquals(2, repository.fileScores(repository.allFiles().map { it.path }).size,
                        "Normal scoring must finish before the first source read")
                    reads += file.path
                    ExifReadResult(ExifStatus.EXTRACTED, ExifMetadata(capturedAt = "2013:06:25 12:00:00"))
                }
            })
        try {
            runCurrent()
            assertTrue(reads.isEmpty())
            assertTrue(container.scanController.start(root))
            container.exifQueue.state.first { it.phase == ExifQueuePhase.COMPLETED }
            assertTrue(container.scanController.state.value is ScanState.Completed)
            assertEquals(2, reads.size)
            val files = container.repository.allFiles()
            assertEquals(2, container.repository.fileExif(files.map { it.path }).size)
            val scores = container.repository.fileScores(files.map { it.path })
            assertEquals(2, scores.size)
            assertTrue(scores.all { score -> score.factors.any { it.key == "exif-date-match" } })
        } finally {
            container.shutdown()
        }
    }

    @Test
    fun acceptingScanImmediatelyRejectsManualQueueBeforeScanDispatcherRuns() = runTest(timeout = 10.seconds) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val root = "/archive".toPath()
        val container = createAppContainer(dispatcher, dispatcher, openDriver = { createInMemoryDriver() },
            createScanner = { _, _ ->
                object : InventoryScanner {
                    override suspend fun startScan(root: Path, onEvent: suspend (ScanEvent) -> Unit) = awaitCancellation()
                    override suspend fun resume(onEvent: suspend (ScanEvent) -> Unit) = awaitCancellation()
                }
            })
        try {
            runCurrent()
            assertTrue(container.scanController.start(root))
            assertFalse(container.exifQueue.start(root))
            assertFalse(container.exifQueue.start(emptyList()))
            container.scanController.pause()
            container.scanController.state.first { it is ScanState.Paused }
            assertTrue(container.exifQueue.start(emptyList()), "Cancelled startup must release its scan gate")
        } finally {
            container.shutdown()
        }
    }

    @Test
    fun newScanWaitsForExifCancellationAndRejectsManualQueueWhileScanning() = runTest(timeout = 10.seconds) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val root = "/archive".toPath()
        val reading = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val scanEntered = CompletableDeferred<Unit>()
        var sourceStopped = false
        val container = createAppContainer(dispatcher, dispatcher, openDriver = { createInMemoryDriver() },
            createScanner = { _, _ ->
                object : InventoryScanner {
                    override suspend fun startScan(root: Path, onEvent: suspend (ScanEvent) -> Unit) {
                        assertTrue(sourceStopped)
                        scanEntered.complete(Unit)
                        awaitCancellation()
                    }
                    override suspend fun resume(onEvent: suspend (ScanEvent) -> Unit) = startScan(root, onEvent)
                }
            }, createExifSource = {
                ExifSource {
                    try {
                        reading.complete(Unit)
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            cleaning.complete(Unit)
                            releaseCleanup.await()
                            sourceStopped = true
                        }
                    }
                }
            })
        try {
            seedAmbiguousDatedGroup(container.repository, root)
            assertTrue(container.exifQueue.start(root))
            reading.await()
            assertTrue(container.scanController.start(root))
            cleaning.await()
            assertFalse(scanEntered.isCompleted)
            assertFalse(container.exifQueue.start(root))
            releaseCleanup.complete(Unit)
            scanEntered.await()
            assertFalse(container.exifQueue.start(root))
            assertFalse(container.exifQueue.start(container.repository.duplicateCandidates(root)))
            container.scanController.pause()
            container.scanController.state.first { it is ScanState.Paused }
        } finally {
            releaseCleanup.complete(Unit)
            container.shutdown()
        }
    }

    @Test
    fun shutdownDrainsCancelledExifSourceBeforeClosingDatabase() = runTest(timeout = 10.seconds) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val root = "/archive".toPath()
        val reading = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var sourceStopped = false
        var closed = false
        val container = createAppContainer(dispatcher, dispatcher, openDriver = {
            val driver = createInMemoryDriver()
            object : SqlDriver by driver {
                override fun close() {
                    assertTrue(sourceStopped)
                    closed = true
                    driver.close()
                }
            }
        }, createScanner = { _, _ ->
            object : InventoryScanner {
                override suspend fun startScan(root: Path, onEvent: suspend (ScanEvent) -> Unit) = Unit
                override suspend fun resume(onEvent: suspend (ScanEvent) -> Unit) = Unit
            }
        }, createExifSource = {
            ExifSource {
                try {
                    reading.complete(Unit)
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleaning.complete(Unit)
                        release.await()
                        sourceStopped = true
                    }
                }
            }
        })
        try {
            seedAmbiguousDatedGroup(container.repository, root)
            assertTrue(container.exifQueue.start(root))
            reading.await()
            val stopping = async { container.shutdown() }
            cleaning.await()
            assertFalse(closed)
            assertFalse(container.exifQueue.start(root))
            release.complete(Unit)
            stopping.await()
            assertTrue(closed)
        } finally {
            release.complete(Unit)
            container.shutdown()
        }
    }

    @Test
    fun shutdownPermanentlyBlocksQueueBeforeScanCleanupCanUnblockIt() = runTest(timeout = 10.seconds) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val root = "/archive".toPath()
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var reads = 0
        val container = createAppContainer(dispatcher, dispatcher, openDriver = { createInMemoryDriver() },
            createScanner = { _, _ ->
                object : InventoryScanner {
                    override suspend fun startScan(root: Path, onEvent: suspend (ScanEvent) -> Unit) {
                        try {
                            entered.complete(Unit)
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) {
                                cleaning.complete(Unit)
                                release.await()
                            }
                        }
                    }
                    override suspend fun resume(onEvent: suspend (ScanEvent) -> Unit) = Unit
                }
            }, createExifSource = { ExifSource { reads++; ExifReadResult(ExifStatus.NO_EXIF) } })
        try {
            container.scanController.start(root)
            entered.await()
            val stopping = async { container.shutdown() }
            cleaning.await()
            assertFalse(container.exifQueue.start(root))
            release.complete(Unit)
            stopping.await()
            assertFalse(container.exifQueue.start(root))
            assertFalse(container.exifQueue.start(emptyList()))
            assertEquals(0, reads)
        } finally {
            release.complete(Unit)
            container.shutdown()
        }
    }

    @Test
    fun failedExifSourceConstructionClosesDriverWithoutStartingScanner() = runTest(timeout = 10.seconds) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var closed = false
        var scannerCreated = false
        assertFailsWith<IllegalStateException> {
            createAppContainer(dispatcher, dispatcher, openDriver = {
                val driver = createInMemoryDriver()
                object : SqlDriver by driver {
                    override fun close() {
                        closed = true
                        driver.close()
                    }
                }
            }, createScanner = { _, _ ->
                scannerCreated = true
                error("Scanner must not start")
            }, createExifSource = { error("EXIF source construction failed") })
        }
        assertTrue(closed)
        assertFalse(scannerCreated)
    }

    @Test
    fun failedScannerConstructionClosesDriverAndNextStartupSucceeds() = runTest(timeout = 10.seconds) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var opens = 0
        var closes = 0
        val openDriver = {
            opens++
            val driver = createInMemoryDriver()
            object : SqlDriver by driver {
                override fun close() {
                    closes++
                    driver.close()
                }
            }
        }
        assertFailsWith<IllegalStateException> {
            createAppContainer(dispatcher, dispatcher, openDriver = openDriver, createScanner = { _, _ ->
                error("scanner construction failed")
            })
        }
        assertEquals(1, opens)
        assertEquals(1, closes)
        val container = createAppContainer(dispatcher, dispatcher, openDriver = openDriver, createScanner = { _, _ ->
            object : InventoryScanner {
                override suspend fun startScan(root: Path, onEvent: suspend (ScanEvent) -> Unit) = Unit
                override suspend fun resume(onEvent: suspend (ScanEvent) -> Unit) = Unit
            }
        })
        try {
            container.repository.seedRoot("/recovered".toPath())
            assertEquals(listOf("/recovered".toPath()), container.repository.allDirectories().map { it.path })
        } finally {
            container.shutdown()
        }
        assertEquals(2, opens)
        assertEquals(2, closes)
    }

    @Test
    fun cancelledShutdownDrainsExternalFilesystemWorkBeforeClosingDriver() = runTest(timeout = 10.seconds) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var filesystemFinished = false
        var closes = 0
        val container = createAppContainer(dispatcher, dispatcher, openDriver = {
            val driver = createInMemoryDriver()
            object : SqlDriver by driver {
                override fun close() {
                    assertTrue(filesystemFinished)
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
        val operation = launch {
            container.filesystemLane.run("external file action") {
                entered.complete(Unit)
                release.await()
                filesystemFinished = true
            }
        }
        try {
            entered.await()
            val first = launch(start = CoroutineStart.UNDISPATCHED) { container.shutdown() }
            first.cancel()
            val second = async(start = CoroutineStart.UNDISPATCHED) { container.shutdown() }
            runCurrent()
            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
            assertEquals(0, closes)
            release.complete(Unit)
            operation.join()
            first.join()
            second.await()
            assertEquals(1, closes)
            assertFailsWith<IllegalStateException> {
                container.filesystemLane.run("late file action") { error("must not execute") }
            }
            assertFailsWith<IllegalStateException> { container.repository.summary() }
        } finally {
            release.complete(Unit)
            operation.join()
            container.shutdown()
        }
    }

    @Test
    fun shutdownDrainsExternalRepositoryTransactionBeforeClosingDriver() = runTest(timeout = 10.seconds) {
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
    fun startupAndShutdownUseIoAndConcurrentShutdownWaitsForScanCleanup() = runTest(timeout = 10.seconds) {
        val worker = StandardTestDispatcher(testScheduler)
        val io = TrackingIoDispatcher(worker)
        val ui = TrackingIoDispatcher(worker)
        val entered = CompletableDeferred<Unit>()
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        var cleaned = false
        var closes = 0
        val scanner = object : InventoryScanner {
            override suspend fun startScan(root: Path, onEvent: suspend (ScanEvent) -> Unit) {
                try {
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleanupEntered.complete(Unit)
                        releaseCleanup.await()
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
        cleanupEntered.await()
        runCurrent()
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        assertEquals(0, closes)
        releaseCleanup.complete(Unit)
        advanceUntilIdle()
        first.await()
        second.await()
        assertEquals(1, closes)
    }

    @Test
    fun cancellationDuringStartupDoesNotLeakOpenedDriver() = runTest(timeout = 10.seconds) {
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
