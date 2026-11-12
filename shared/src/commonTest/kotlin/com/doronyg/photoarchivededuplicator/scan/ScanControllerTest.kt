package com.doronyg.photoarchivededuplicator.scan

import com.doronyg.photoarchivededuplicator.IoLane
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Virtual time (testScheduler.currentTime) is still marked experimental in kotlinx-coroutines-test
@OptIn(ExperimentalCoroutinesApi::class)
class ScanControllerTest {
    private val env = ScanTestEnvironment()
    private val root = env.root

    @AfterTest
    fun tearDown() = env.close()

    private fun TestScope.controller(config: ScanConfig = ScanConfig()) = ScanController(
        scanner = with(env) { scanner(config) },
        // Not backgroundScope: advanceUntilIdle() doesn't wait for background work
        scope = this,
        repository = env.repository,
        clock = { testScheduler.currentTime },
    )

    private fun writeTree(): Int {
        val files = listOf(root / "a" / "1.jpg", root / "a" / "2.jpg", root / "b" / "3.jpg")
        files.forEach { env.writeFile(it) }
        return files.size
    }

    @Test
    fun initialStateIsIdle() = env.test {
        assertIs<ScanState.Idle>(controller().state.value)
    }

    @Test
    fun startDispatchesInsteadOfEnteringScanOnCaller() = env.test {
        val worker = TrackingIoDispatcher(StandardTestDispatcher(testScheduler))
        val controller = ScanController(
            scanner = with(env) { scanner() },
            scope = CoroutineScope(coroutineContext + worker),
            repository = env.repository,
        )
        runCurrent() // Complete restoration so joining it cannot hide undispatched execution.
        val dispatched = worker.dispatchCount
        assertTrue(controller.start(root))
        assertEquals(dispatched + 1, worker.dispatchCount)
        controller.pause()
        advanceUntilIdle()
        assertIs<ScanState.Paused>(controller.state.value)
    }

    @Test
    fun startRunsScanToCompletionAndReportsTotals() = env.test {
        val fileCount = writeTree()
        val controller = controller()

        assertTrue(controller.start(root))
        advanceUntilIdle()

        val progress = assertIs<ScanState.Completed>(controller.state.value).progress
        assertEquals(3L, progress.scannedDirectories)
        assertEquals(fileCount.toLong(), progress.filesFound)
        assertEquals(0L, progress.pendingDirectories)
        assertEquals(0L, progress.failedDirectories)
        assertNull(progress.currentDirectory)
    }

    @Test
    fun reportsProgressWhileRunning() = env.test {
        writeTree()
        val controller = controller()
        var observed: ScanState? = null
        // Directories are processed in path order: root, a, b
        env.fs.onList = { if (it == root / "b") observed = controller.state.value }

        controller.start(root)
        advanceUntilIdle()

        val progress = assertIs<ScanState.Running>(observed).progress
        assertEquals(root / "b", progress.currentDirectory)
        assertEquals(2L, progress.scannedDirectories)
        assertEquals(1L, progress.pendingDirectories)
    }

    @Test
    fun startIsRejectedWhileRunning() = env.test {
        writeTree()
        val controller = controller()
        var secondStartAccepted: Boolean? = null
        env.fs.onList = {
            if (secondStartAccepted == null) launch { secondStartAccepted = controller.start("/other".toPath()) }
        }

        controller.start(root)
        advanceUntilIdle()

        assertEquals(false, secondStartAccepted)
        assertIs<ScanState.Completed>(controller.state.value)
        assertTrue(env.fs.listCalls.none { it == "/other".toPath() })
    }

    @Test
    fun pauseStopsScanAndResumeFinishesIt() = env.test {
        writeTree()
        val controller = controller()
        env.fs.onList = { if (it == root / "a") controller.pause() }

        controller.start(root)
        advanceUntilIdle()

        assertIs<ScanState.Paused>(controller.state.value)
        assertTrue(env.repository.allDirectories().any { it.status != DirectoryStatus.COMPLETED })

        env.fs.onList = {}
        assertTrue(controller.resume())
        advanceUntilIdle()

        assertIs<ScanState.Completed>(controller.state.value)
        assertTrue(env.repository.allDirectories().all { it.status == DirectoryStatus.COMPLETED })
    }

    @Test
    fun immediatePauseDoesNotLeaveControllerRunning() = env.test {
        writeTree()
        val controller = controller()
        controller.start(root)
        controller.pause()
        advanceUntilIdle()
        assertIs<ScanState.Paused>(controller.state.value)
        assertTrue(controller.start(root))
        advanceUntilIdle()
        assertIs<ScanState.Completed>(controller.state.value)
    }

    @Test
    fun pauseDuringFreshSessionInsertLeavesRootQueuedForRecreatedController() = env.test {
        pauseDuringInitializationAndResume(rescan = false, duringInsert = true)
    }

    @Test
    fun pauseDuringRescanSessionInsertLeavesRootQueuedForRecreatedController() = env.test {
        pauseDuringInitializationAndResume(rescan = true, duringInsert = true)
    }

    @Test
    fun pauseAfterFreshSessionInsertLeavesRootQueuedForRecreatedController() = env.test {
        pauseDuringInitializationAndResume(rescan = false, duringInsert = false)
    }

    @Test
    fun pauseAfterRescanSessionInsertLeavesRootQueuedForRecreatedController() = env.test {
        pauseDuringInitializationAndResume(rescan = true, duringInsert = false)
    }

    private suspend fun TestScope.pauseDuringInitializationAndResume(rescan: Boolean, duringInsert: Boolean) {
        env.writeFile(root / "old.jpg")
        if (rescan) {
            controller().start(root)
            advanceUntilIdle()
            assertEquals(DirectoryStatus.COMPLETED, env.repository.directory(root)!!.status)
        }
        val newFile = root / "new.jpg"
        env.writeFile(newFile)
        val scanner = with(env) { scanner() }
        lateinit var controller: ScanController
        var paused = false
        val boundaryScanner = object : InventoryScanner {
            override suspend fun startScan(root: okio.Path, onEvent: suspend (ScanEvent) -> Unit) {
                if (!duringInsert) {
                    paused = true
                    controller.pause()
                }
                scanner.startScan(root, onEvent)
            }
            override suspend fun resume(onEvent: suspend (ScanEvent) -> Unit) = scanner.resume(onEvent)
        }
        controller = ScanController(boundaryScanner, this, env.repository, clock = { testScheduler.currentTime })
        env.beforeStatement = { sql ->
            if (duringInsert && !paused && sql.startsWith("INSERT OR REPLACE INTO scanSession")) {
                paused = true
                controller.pause()
            }
        }
        controller.start(root)
        advanceUntilIdle()
        env.beforeStatement = {}
        assertTrue(paused)
        assertIs<ScanState.Paused>(controller.state.value)
        assertEquals(root, env.repository.currentScanSession()!!.root)
        assertFalse(env.repository.currentScanSession()!!.finished)
        assertEquals(DirectoryStatus.PENDING, env.repository.directory(root)?.status)

        val recreated = controller()
        advanceUntilIdle()
        assertIs<ScanState.Paused>(recreated.state.value)
        recreated.resume()
        advanceUntilIdle()
        assertIs<ScanState.Completed>(recreated.state.value)
        assertEquals(DirectoryStatus.COMPLETED, env.repository.directory(root)!!.status)
        assertEquals(newFile, env.repository.file(newFile)?.path)
    }

    @Test
    fun resumeContinuesWorkLeftByPreviousSession() = env.test {
        writeTree()
        env.repository.seedRoot(root)
        val controller = controller()

        controller.resume()
        advanceUntilIdle()

        assertEquals(3L, assertIs<ScanState.Completed>(controller.state.value).progress.scannedDirectories)
    }

    @Test
    fun legacyResumeDoesNotRequeueCompletedRoot() = env.test {
        env.repository.seedRoot(root)
        env.repository.recordListing(root, DirectoryListing(emptyList(), emptyList()), 123)
        assertNull(env.repository.currentScanSession())

        val controller = controller()
        controller.resume()
        advanceUntilIdle()

        assertEquals(0L, assertIs<ScanState.Completed>(controller.state.value).progress.scannedDirectories)
        assertTrue(env.fs.listCalls.isEmpty())
        assertEquals(123L, env.repository.directory(root)!!.lastScanned)
    }

    @Test
    fun rootQueueFailureRollsBackNewSessionAndSelection() = env.test {
        env.repository.beginScanSession(root)
        env.repository.recordListing(root, DirectoryListing(emptyList(), emptyList()), 123)
        env.repository.checkpointScanSession(ScanSessionRecord(root, 456, finished = true))
        val savedSession = env.repository.currentScanSession()
        val savedDirectories = env.repository.allDirectories()
        val other = "/other".toPath()
        for (target in listOf(other, root)) {
            env.beforeStatement = { sql ->
                if (sql.startsWith("INSERT OR IGNORE INTO scanDirectory")) error("Queue write failed")
            }
            try {
                env.repository.beginScanSession(target)
                error("Expected queue write failure")
            } catch (e: IllegalStateException) {
                assertEquals("Queue write failed", e.message)
            } finally {
                env.beforeStatement = {}
            }
            assertEquals(savedSession, env.repository.currentScanSession())
            assertEquals(savedDirectories, env.repository.allDirectories())
            assertNull(env.repository.directory(other))
        }
    }

    @Test
    fun failedDirectoriesAreCountedAndElapsedTimeIncludesBackoff() = env.test {
        writeTree()
        val config = ScanConfig()
        env.fs.failListing(root / "a")
        val controller = controller(config)

        controller.start(root)
        advanceUntilIdle()

        val progress = assertIs<ScanState.Completed>(controller.state.value).progress
        assertEquals(1L, progress.failedDirectories)
        assertEquals(2L, progress.scannedDirectories)
        assertEquals(0L, progress.pendingDirectories)
        // Backoff doubles after every failed attempt except the last
        val expectedBackoff = (0 until config.maxAttempts - 1).sumOf { config.initialBackoff.inWholeMilliseconds shl it }
        assertEquals(expectedBackoff, progress.elapsedMillis)
    }

    @Test
    fun unexpectedErrorMovesToFailedState() = env.test {
        writeTree()
        val message = "disk exploded"
        env.fs.onList = { error(message) }
        val controller = controller()

        controller.start(root)
        advanceUntilIdle()

        assertEquals(message, assertIs<ScanState.Failed>(controller.state.value).message)
        assertFalse(controller.state.value is ScanState.Running)
    }

    @Test
    fun timerTicksWithoutEventsAndFinalizesWorkAfterLastEvent() = env.test {
        val scanner = object : InventoryScanner {
            override suspend fun startScan(root: okio.Path, onEvent: suspend (ScanEvent) -> Unit) {
                delay(2_450)
            }
            override suspend fun resume(onEvent: suspend (ScanEvent) -> Unit) = Unit
        }
        val controller = ScanController(scanner, this, env.repository, clock = { testScheduler.currentTime })
        controller.start(root)
        advanceTimeBy(1_100)
        runCurrent()
        val live = assertIs<ScanState.Running>(controller.state.value).progress
        assertEquals(1_000L, live.elapsedMillis)
        assertEquals(1_000L, live.totalElapsedMillis)
        advanceUntilIdle()
        assertEquals(2_450L, assertIs<ScanState.Completed>(controller.state.value).progress.totalElapsedMillis)
        assertEquals(2_450L, env.repository.currentScanSession()!!.activeMillis)
    }

    @Test
    fun progressIsReducedByOwnerNotScannerCallback() = env.test {
        val producer = TrackingIoDispatcher(StandardTestDispatcher(testScheduler))
        val scanner = object : InventoryScanner {
            override suspend fun startScan(root: okio.Path, onEvent: suspend (ScanEvent) -> Unit) {
                withContext(producer) {
                    onEvent(ScanEvent.Started(1))
                    onEvent(ScanEvent.DirectoryCompleted(root, 0, 7))
                }
            }
            override suspend fun resume(onEvent: suspend (ScanEvent) -> Unit) = Unit
        }
        val controller = ScanController(scanner, this, env.repository, clock = {
            check(!producer.isRunningTask) { "Progress sampled on scanner callback dispatcher" }
            testScheduler.currentTime
        })

        controller.start(root)
        advanceUntilIdle()

        val progress = assertIs<ScanState.Completed>(controller.state.value).progress
        assertEquals(1L, progress.scannedDirectories)
        assertEquals(7L, progress.filesFound)
        assertEquals(0L, progress.pendingDirectories)
    }

    @Test
    fun frequentEventsDoNotPostponeCheckpointsOrLoseAccounting() = env.test {
        val scanner = object : InventoryScanner {
            override suspend fun startScan(root: okio.Path, onEvent: suspend (ScanEvent) -> Unit) {
                onEvent(ScanEvent.Started(1_200))
                repeat(1_200) {
                    delay(10)
                    onEvent(ScanEvent.DirectoryCompleted(root, 0, 2))
                }
            }
            override suspend fun resume(onEvent: suspend (ScanEvent) -> Unit) = Unit
        }
        val controller = ScanController(scanner, this, env.repository, clock = { testScheduler.currentTime })
        controller.start(root)
        advanceTimeBy(10_100)
        runCurrent()
        assertEquals(10_000L, env.repository.currentScanSession()!!.activeMillis)

        advanceUntilIdle()
        val progress = assertIs<ScanState.Completed>(controller.state.value).progress
        assertEquals(1_200L, progress.scannedDirectories)
        assertEquals(2_400L, progress.filesFound)
        assertEquals(0L, progress.pendingDirectories)
        assertEquals(12_000L, progress.elapsedMillis)
    }

    @Test
    fun stalledCheckpointDoesNotBlockTimerOrScannerEvents() = env.test {
        val driver = createInMemoryDriver()
        val lane = IoLane("checkpoint-test", StandardTestDispatcher(testScheduler))
        val repository = DirectoryRepository(createFilesDatabase(driver), databaseLane = lane)
        val release = CompletableDeferred<Unit>()
        val scanner = object : InventoryScanner {
            override suspend fun startScan(root: okio.Path, onEvent: suspend (ScanEvent) -> Unit) {
                onEvent(ScanEvent.Started(1))
                delay(6_500)
                onEvent(ScanEvent.DirectoryCompleted(root, 0, 3))
                delay(100_000)
            }
            override suspend fun resume(onEvent: suspend (ScanEvent) -> Unit) = Unit
        }
        val controller = ScanController(scanner, this, repository, clock = { testScheduler.currentTime })
        try {
            controller.start(root)
            runCurrent()
            val blocked = launch { lane.run("stalled transaction") { release.await() } }
            runCurrent()

            advanceTimeBy(7_100)
            runCurrent()
            val progress = assertIs<ScanState.Running>(controller.state.value).progress
            assertEquals(7_000L, progress.elapsedMillis)
            assertEquals(1L, progress.scannedDirectories)
            assertEquals(3L, progress.filesFound)

            release.complete(Unit)
            blocked.join()
            runCurrent()
            assertEquals(5_000L, repository.currentScanSession()!!.activeMillis)
        } finally {
            release.complete(Unit)
            controller.pause()
            advanceUntilIdle()
            lane.close { driver.close() }
        }
        assertEquals(7_100L, assertIs<ScanState.Paused>(controller.state.value).progress.totalElapsedMillis)
    }

    @Test
    fun resumeAcrossControllersExcludesPausedTimeAndNewRootResetsTotal() = env.test {
        val scanner = object : InventoryScanner {
            override suspend fun startScan(root: okio.Path, onEvent: suspend (ScanEvent) -> Unit) { delay(100_000) }
            override suspend fun resume(onEvent: suspend (ScanEvent) -> Unit) { delay(2_350) }
        }
        fun newController() = ScanController(scanner, this, env.newRepository(), clock = { testScheduler.currentTime })
        val first = newController()
        first.start(root)
        advanceTimeBy(1_250)
        first.pause()
        runCurrent()
        assertEquals(1_250L, assertIs<ScanState.Paused>(first.state.value).progress.totalElapsedMillis)
        advanceTimeBy(40_000)
        val second = newController()
        runCurrent()
        assertEquals(1_250L, assertIs<ScanState.Paused>(second.state.value).progress.totalElapsedMillis)
        second.resume()
        advanceUntilIdle()
        val completed = assertIs<ScanState.Completed>(second.state.value).progress
        assertEquals(2_350L, completed.elapsedMillis)
        assertEquals(3_600L, completed.totalElapsedMillis)
        second.start("/different".toPath())
        advanceTimeBy(500)
        second.pause()
        runCurrent()
        assertEquals(500L, assertIs<ScanState.Paused>(second.state.value).progress.totalElapsedMillis)
        assertEquals("/different".toPath(), env.repository.currentScanSession()!!.root)
        env.repository.seedRoot(root)
        second.resume(root)
        advanceUntilIdle()
        assertEquals(5_950L, assertIs<ScanState.Completed>(second.state.value).progress.totalElapsedMillis)
        val reopened = newController()
        runCurrent()
        assertEquals(5_950L, assertIs<ScanState.Completed>(reopened.state.value).progress.totalElapsedMillis)
    }

    @Test
    fun scansOnlySelectedRootAndKeepsOtherPendingInventory() = env.test {
        val other = "/archive-other".toPath()
        env.writeFile(root / "a.jpg")
        env.writeFile(other / "b.jpg")
        env.repository.seedRoot(other)
        val controller = controller()
        controller.start(root)
        advanceUntilIdle()
        assertEquals(listOf(root), env.fs.listCalls)
        assertEquals(DirectoryStatus.PENDING, env.repository.directory(other)!!.status)
        controller.resume()
        advanceUntilIdle()
        assertEquals(listOf(root), env.fs.listCalls)
    }

    @Test
    fun checkpointsPeriodicallyAndReportsPersistenceFailure() = env.test {
        val scanner = object : InventoryScanner {
            override suspend fun startScan(root: okio.Path, onEvent: suspend (ScanEvent) -> Unit) { delay(100_000) }
            override suspend fun resume(onEvent: suspend (ScanEvent) -> Unit) = Unit
        }
        val controller = ScanController(scanner, this, env.repository, clock = { testScheduler.currentTime })
        controller.start(root)
        advanceTimeBy(6_100)
        runCurrent()
        assertEquals(5_000L, env.repository.currentScanSession()!!.activeMillis)
        assertEquals(6_000L, assertIs<ScanState.Running>(controller.state.value).progress.elapsedMillis)
        env.beforeStatement = { if (it.startsWith("UPDATE scanSession")) error("Database full") }
        advanceUntilIdle()
        assertEquals("Database full", assertIs<ScanState.Failed>(controller.state.value).message)
        env.beforeStatement = {}
    }

    @Test
    fun restoreDatabaseErrorsAreVisible() = env.test {
        env.beforeStatement = { if (it.contains("FROM scanSession")) error("Cannot read scan session") }
        val controller = controller()
        advanceUntilIdle()
        assertEquals("Cannot read scan session", assertIs<ScanState.Failed>(controller.state.value).message)
        env.beforeStatement = {}
    }

    @Test
    fun failureIncludesTimeAfterLastEvent() = env.test {
        val scanner = object : InventoryScanner {
            override suspend fun startScan(root: okio.Path, onEvent: suspend (ScanEvent) -> Unit) {
                delay(1_234)
                error("Share disconnected")
            }
            override suspend fun resume(onEvent: suspend (ScanEvent) -> Unit) = Unit
        }
        val controller = ScanController(scanner, this, env.repository, clock = { testScheduler.currentTime })
        controller.start(root)
        advanceUntilIdle()
        val state = assertIs<ScanState.Failed>(controller.state.value)
        assertEquals("Share disconnected", state.message)
        assertEquals(1_234L, state.progress.totalElapsedMillis)
        assertEquals(1_234L, env.repository.currentScanSession()!!.activeMillis)
    }

    @Test
    fun pauseFreezesTimeWhilePreviousRunUnwindsAndRejectsConcurrentResume() = env.test {
        val scanner = object : InventoryScanner {
            override suspend fun startScan(root: okio.Path, onEvent: suspend (ScanEvent) -> Unit) {
                try {
                    delay(100_000)
                } finally {
                    withContext(NonCancellable) { delay(2_000) }
                }
            }
            override suspend fun resume(onEvent: suspend (ScanEvent) -> Unit) { delay(750) }
        }
        val controller = ScanController(scanner, this, env.repository, clock = { testScheduler.currentTime })
        controller.start(root)
        advanceTimeBy(1_250)
        controller.pause()
        runCurrent()
        assertFalse(controller.resume())
        advanceUntilIdle()
        assertEquals(1_250L, assertIs<ScanState.Paused>(controller.state.value).progress.totalElapsedMillis)
        controller.resume()
        advanceUntilIdle()
        val progress = assertIs<ScanState.Completed>(controller.state.value).progress
        assertEquals(750L, progress.elapsedMillis)
        assertEquals(2_000L, progress.totalElapsedMillis)
    }
}
