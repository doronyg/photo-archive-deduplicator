package com.doronyg.photoarchivededuplicator.scan

import com.doronyg.photoarchivededuplicator.IoLane
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ScanControllerConcurrencyTest {
    private val root = "/archive".toPath()
    private val other = "/other".toPath()

    private class Fixture(private val test: TestScope) {
        private val io = TrackingIoDispatcher(StandardTestDispatcher(test.testScheduler))
        val lane = IoLane("controller-concurrency", io)
        private val driver = createInMemoryDriver()
        var beforeStatement: (String) -> Unit = {}
        val repository = DirectoryRepository(
            createFilesDatabase(IoGuardedDriver(driver, io) { beforeStatement(it) }),
            databaseLane = lane,
        )
        private val owner = Job(test.coroutineContext[Job])
        val scope = CoroutineScope(test.coroutineContext + owner)
        val invocations = mutableListOf<Path>()
        private val gates = mutableListOf<CompletableDeferred<Unit>>()

        fun gate() = CompletableDeferred<Unit>().also { gates += it }

        fun controller(body: suspend (Path, suspend (ScanEvent) -> Unit) -> Unit): ScanController {
            val scanner = object : InventoryScanner {
                override suspend fun startScan(root: Path, onEvent: suspend (ScanEvent) -> Unit) {
                    invocations += root
                    body(root, onEvent)
                }

                override suspend fun resume(onEvent: suspend (ScanEvent) -> Unit) {
                    val root = repository.currentScanSession()!!.root
                    invocations += root
                    body(root, onEvent)
                }
            }
            return ScanController(scanner, scope, repository, clock = { test.testScheduler.currentTime })
        }

        suspend fun finish(root: Path, emit: suspend (ScanEvent) -> Unit, files: Int = 3) {
            emit(ScanEvent.Started(1))
            repository.recordListing(root, listing(root, files), 17)
            emit(ScanEvent.DirectoryCompleted(root, 0, files))
        }

        fun listing(root: Path, files: Int) = DirectoryListing(
            emptyList(),
            List(files) { DirectoryListing.Entry(root / "$it.jpg", 1, null) },
        )

        suspend fun assertCompleted(controller: ScanController, root: Path, files: Int = 3) {
            val progress = assertIs<ScanState.Completed>(controller.state.value).progress
            assertEquals(root, progress.root)
            assertEquals(1L, progress.scannedDirectories)
            assertEquals(files.toLong(), progress.filesFound)
            assertEquals(0L, progress.pendingDirectories)
            assertEquals(0L, progress.failedDirectories)
            assertEquals(null, progress.currentDirectory)
            assertEquals(ScanSessionRecord(root, progress.totalElapsedMillis, finished = true),
                repository.currentScanSession())
            assertEquals(DirectoryStatus.COMPLETED, repository.directory(root)!!.status)
            assertEquals(files, repository.allFiles().count { it.directoryPath == root })
        }

        suspend fun close() {
            gates.forEach { it.complete(Unit) }
            owner.cancelAndJoin()
            lane.close { driver.close() }
        }
    }

    private suspend fun TestScope.contend(firstResume: Boolean, secondResume: Boolean) {
        val f = Fixture(this)
        try {
            if (firstResume || secondResume) f.repository.beginScanSession(root)
            val entered = f.gate()
            val finish = f.gate()
            val controller = f.controller { selected, emit ->
                entered.complete(Unit)
                finish.await()
                f.finish(selected, emit)
            }
            runCurrent()
            val callersReady = Channel<Unit>(2)
            val releaseCallers = f.gate()
            fun contender(resume: Boolean, selected: Path) = async(start = CoroutineStart.UNDISPATCHED) {
                callersReady.send(Unit)
                releaseCallers.await()
                if (resume) controller.resume(root) else controller.start(selected)
            }
            val firstRoot = if (!firstResume && secondResume) other else root
            val secondRoot = if (secondResume) root else other
            val first = contender(firstResume, firstRoot)
            val second = contender(secondResume, secondRoot)
            repeat(2) { callersReady.receive() }
            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
            releaseCallers.complete(Unit)
            val accepted = listOf(first.await(), second.await())
            assertEquals(1, accepted.count { it })
            entered.await()
            runCurrent()
            val selected = if (accepted[0]) firstRoot else secondRoot
            assertEquals(listOf(selected), f.invocations)
            assertEquals(selected, assertIs<ScanState.Running>(controller.state.value).progress.root)
            assertEquals(ScanSessionRecord(selected, 0), f.repository.currentScanSession())
            finish.complete(Unit)
            runCurrent()
            f.assertCompleted(controller, selected)
            assertEquals(listOf(selected), f.invocations)
            callersReady.close()
        } finally {
            f.close()
        }
    }

    @Test
    fun simultaneousStartsAcceptExactlyOneRootAndPersistOnlyItsRun() = runTest(timeout = 10.seconds) {
        contend(firstResume = false, secondResume = false)
    }

    @Test
    fun simultaneousStartAndResumeAcceptExactlyOneRun() = runTest(timeout = 10.seconds) {
        contend(firstResume = false, secondResume = true)
    }

    @Test
    fun simultaneousResumesInvokeScannerExactlyOnce() = runTest(timeout = 10.seconds) {
        contend(firstResume = true, secondResume = true)
    }

    @Test
    fun cancelledRunRejectsStartAndResumeUntilCleanupAndCheckpointFinish() = runTest(timeout = 10.seconds) {
        val f = Fixture(this)
        try {
            val entered = f.gate()
            val cleaning = f.gate()
            val releaseCleanup = f.gate()
            val controller = f.controller { selected, emit ->
                if (selected == root) {
                    emit(ScanEvent.Started(1))
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            cleaning.complete(Unit)
                            releaseCleanup.await()
                        }
                    }
                } else {
                    f.finish(selected, emit)
                }
            }
            assertTrue(controller.start(root))
            entered.await()
            controller.pause()
            cleaning.await()
            assertFalse(controller.start(other))
            assertFalse(controller.resume(root))
            val databaseHeld = f.gate()
            val releaseDatabase = f.gate()
            val holder = f.scope.launch {
                f.lane.run("hold final checkpoint") {
                    databaseHeld.complete(Unit)
                    releaseDatabase.await()
                }
            }
            databaseHeld.await()
            releaseCleanup.complete(Unit)
            runCurrent()
            assertIs<ScanState.Running>(controller.state.value)
            assertFalse(controller.start(other))
            assertFalse(controller.resume(root))
            assertEquals(listOf(root), f.invocations)
            releaseDatabase.complete(Unit)
            holder.join()
            runCurrent()
            val paused = assertIs<ScanState.Paused>(controller.state.value).progress
            assertEquals(root, paused.root)
            assertEquals(0L, paused.scannedDirectories)
            assertEquals(1L, paused.pendingDirectories)
            assertEquals(ScanSessionRecord(root, paused.totalElapsedMillis), f.repository.currentScanSession())
            assertEquals(DirectoryStatus.PENDING, f.repository.directory(root)!!.status)
            assertTrue(controller.start(other))
            runCurrent()
            f.assertCompleted(controller, other)
            assertEquals(listOf(root, other), f.invocations)
        } finally {
            f.close()
        }
    }

    @Test
    fun oldCallbackAfterCancellationCannotChangeNewRunOrItsCheckpoint() = runTest(timeout = 10.seconds) {
        val f = Fixture(this)
        try {
            val callbacks = Channel<suspend (ScanEvent) -> Unit>(1)
            val newEntered = f.gate()
            val finishNew = f.gate()
            val controller = f.controller { selected, emit ->
                if (selected == root) {
                    emit(ScanEvent.Started(1))
                    callbacks.send(emit)
                    awaitCancellation()
                } else {
                    newEntered.complete(Unit)
                    finishNew.await()
                    f.finish(selected, emit, files = 2)
                }
            }
            controller.start(root)
            val staleCallback = callbacks.receive()
            controller.pause()
            runCurrent()
            assertIs<ScanState.Paused>(controller.state.value)
            assertTrue(controller.start(other))
            newEntered.await()
            runCurrent()
            val before = assertIs<ScanState.Running>(controller.state.value)
            val staleResult = async {
                runCatching { staleCallback(ScanEvent.DirectoryCompleted(root, 0, 999)) }.exceptionOrNull()
            }
            val error = staleResult.await()
            assertTrue(error is CancellationException || error is ClosedSendChannelException,
                "The old run's cancelled/closed event channel must reject callbacks, got $error")
            assertEquals(before, controller.state.value)
            assertEquals(ScanSessionRecord(other, 0), f.repository.currentScanSession())
            finishNew.complete(Unit)
            runCurrent()
            f.assertCompleted(controller, other, files = 2)
            assertEquals(DirectoryStatus.PENDING, f.repository.directory(root)!!.status)
            assertEquals(listOf(root, other), f.invocations)
            callbacks.close()
        } finally {
            f.close()
        }
    }

    @Test
    fun delayedStartupRestoreCannotOverwriteAnAcceptedStart() = runTest(timeout = 10.seconds) {
        val f = Fixture(this)
        try {
            f.repository.beginScanSession(root)
            f.repository.checkpointScanSession(ScanSessionRecord(root, 123))
            val held = f.gate()
            val release = f.gate()
            val holder = f.scope.launch {
                f.lane.run("hold startup restore") {
                    held.complete(Unit)
                    release.await()
                }
            }
            held.await()
            val entered = f.gate()
            val finish = f.gate()
            val controller = f.controller { selected, emit ->
                entered.complete(Unit)
                finish.await()
                f.finish(selected, emit)
            }
            runCurrent() // Restoration is now queued behind the held database lane.
            assertIs<ScanState.Idle>(controller.state.value)
            val observed = mutableListOf<ScanState>()
            f.scope.launch(UnconfinedTestDispatcher(testScheduler)) {
                controller.state.collect { observed += it }
            }
            assertTrue(controller.start(other))
            observed.clear()
            runCurrent()
            assertIs<ScanState.Running>(controller.state.value)
            assertTrue(f.invocations.isEmpty())
            release.complete(Unit)
            holder.join()
            entered.await()
            assertEquals(other, assertIs<ScanState.Running>(controller.state.value).progress.root)
            assertTrue(observed.all { it is ScanState.Running }, "Restoration must not publish the saved Paused state")
            finish.complete(Unit)
            runCurrent()
            f.assertCompleted(controller, other)
            assertEquals(listOf(other), f.invocations)
        } finally {
            f.close()
        }
    }

    @Test
    fun pauseBeforeLastEventKeepsPersistedWorkButDoesNotPublishStaleAccounting() = runTest(timeout = 10.seconds) {
        val f = Fixture(this)
        try {
            val lastEventReady = f.gate()
            val releaseLastEvent = f.gate()
            val rejected = CompletableDeferred<Throwable?>()
            val controller = f.controller { selected, emit ->
                emit(ScanEvent.Started(1))
                f.repository.recordListing(selected, f.listing(selected, 3), 17)
                withContext(NonCancellable) {
                    lastEventReady.complete(Unit)
                    releaseLastEvent.await()
                    rejected.complete(runCatching {
                        emit(ScanEvent.DirectoryCompleted(selected, 0, 3))
                    }.exceptionOrNull())
                }
            }
            controller.start(root)
            lastEventReady.await()
            controller.pause()
            runCurrent()
            releaseLastEvent.complete(Unit)
            assertIs<CancellationException>(rejected.await())
            runCurrent()
            val progress = assertIs<ScanState.Paused>(controller.state.value).progress
            assertEquals(root, progress.root)
            assertEquals(0L, progress.scannedDirectories)
            assertEquals(0L, progress.filesFound)
            assertEquals(1L, progress.pendingDirectories)
            assertEquals(ScanSessionRecord(root, progress.totalElapsedMillis), f.repository.currentScanSession())
            assertEquals(DirectoryStatus.COMPLETED, f.repository.directory(root)!!.status)
            assertEquals(3, f.repository.allFiles().size)
        } finally {
            f.close()
        }
    }

    @Test
    fun pauseAtFinalCheckpointLeavesTerminalStateConsistentWithPersistence() = runTest(timeout = 10.seconds) {
        val f = Fixture(this)
        try {
            val scanned = f.gate()
            val returnScanner = f.gate()
            val controller = f.controller { selected, emit ->
                f.finish(selected, emit)
                scanned.complete(Unit)
                returnScanner.await()
            }
            controller.start(root)
            scanned.await()
            val held = f.gate()
            val release = f.gate()
            val holder = f.scope.launch {
                f.lane.run("hold completion checkpoint") {
                    held.complete(Unit)
                    release.await()
                }
            }
            held.await()
            returnScanner.complete(Unit)
            runCurrent()
            assertIs<ScanState.Running>(controller.state.value)
            controller.pause()
            assertFalse(controller.start(other))
            assertFalse(controller.resume(root))
            release.complete(Unit)
            holder.join()
            runCurrent()
            val terminal = controller.state.value
            val progress = when (terminal) {
                is ScanState.Completed -> terminal.progress
                is ScanState.Paused -> terminal.progress
                else -> error("Expected Completed or Paused, got $terminal")
            }
            assertEquals(root, progress.root)
            assertEquals(1L, progress.scannedDirectories)
            assertEquals(3L, progress.filesFound)
            assertEquals(0L, progress.pendingDirectories)
            assertEquals(ScanSessionRecord(root, progress.totalElapsedMillis, terminal is ScanState.Completed),
                f.repository.currentScanSession())
            assertEquals(DirectoryStatus.COMPLETED, f.repository.directory(root)!!.status)
            assertEquals(3, f.repository.allFiles().size)
        } finally {
            f.close()
        }
    }

    @Test
    fun oneShotSessionInitializationFailureDoesNotScanAndCanRetry() = runTest(timeout = 10.seconds) {
        val f = Fixture(this)
        try {
            val controller = f.controller { selected, emit -> f.finish(selected, emit) }
            runCurrent()
            var injected = false
            f.beforeStatement = {
                if (!injected && it.startsWith("INSERT OR REPLACE INTO scanSession")) {
                    injected = true
                    error("one-shot initialization")
                }
            }
            assertTrue(controller.start(root))
            runCurrent()
            assertTrue(injected)
            val failed = assertIs<ScanState.Failed>(controller.state.value)
            assertEquals("one-shot initialization", failed.message)
            assertEquals(0L, failed.progress.filesFound)
            assertTrue(f.invocations.isEmpty())
            assertEquals(null, f.repository.currentScanSession())
            assertEquals(null, f.repository.directory(root))
            assertTrue(controller.start(other))
            runCurrent()
            f.assertCompleted(controller, other)
            assertEquals(listOf(other), f.invocations)
        } finally {
            f.close()
        }
    }

    @Test
    fun oneShotPeriodicAndFinalCheckpointFailuresReleaseRunForRetry() = runTest(timeout = 10.seconds) {
        for (periodic in listOf(true, false)) {
            val f = Fixture(this)
            try {
                val entered = f.gate()
                val finish = f.gate()
                val controller = f.controller { selected, emit ->
                    if (selected == root) {
                        emit(ScanEvent.Started(1))
                        entered.complete(Unit)
                        if (periodic) awaitCancellation() else finish.await()
                    } else {
                        f.finish(selected, emit)
                    }
                }
                controller.start(root)
                entered.await()
                var injected = false
                f.beforeStatement = {
                    if (!injected && it.startsWith("UPDATE scanSession")) {
                        injected = true
                        error("one-shot checkpoint")
                    }
                }
                if (periodic) {
                    advanceTimeBy(5_001)
                } else {
                    finish.complete(Unit)
                }
                runCurrent()
                assertTrue(injected)
                val failed = assertIs<ScanState.Failed>(controller.state.value)
                assertEquals("one-shot checkpoint", failed.message)
                assertEquals(root, failed.progress.root)
                assertEquals(0L, failed.progress.scannedDirectories)
                val saved = f.repository.currentScanSession()!!
                assertEquals(root, saved.root)
                assertFalse(saved.finished)
                assertEquals(if (periodic) failed.progress.totalElapsedMillis else 0L, saved.activeMillis)
                assertEquals(DirectoryStatus.PENDING, f.repository.directory(root)!!.status)
                assertTrue(controller.start(other))
                runCurrent()
                f.assertCompleted(controller, other)
                assertEquals(listOf(root, other), f.invocations)
            } finally {
                f.close()
            }
        }
    }

    @Test
    fun retryFromFailurePublicationCannotBeOverwrittenByOldFinalization() = runTest(timeout = 10.seconds) {
        val f = Fixture(this)
        try {
            val fail = f.gate()
            val entered = f.gate()
            val nextEntered = f.gate()
            val finishNext = f.gate()
            val controller = f.controller { selected, emit ->
                if (selected == root) {
                    emit(ScanEvent.Started(1))
                    entered.complete(Unit)
                    fail.await()
                    error("scanner failed")
                } else {
                    nextEntered.complete(Unit)
                    finishNext.await()
                    f.finish(selected, emit, files = 2)
                }
            }
            val retryAccepted = CompletableDeferred<Boolean>()
            f.scope.launch(UnconfinedTestDispatcher(testScheduler)) {
                controller.state.collect {
                    if (it is ScanState.Failed && !retryAccepted.isCompleted) {
                        assertEquals("scanner failed", it.message)
                        retryAccepted.complete(controller.start(other))
                    }
                }
            }
            controller.start(root)
            entered.await()
            fail.complete(Unit)
            assertTrue(retryAccepted.await())
            nextEntered.await()
            runCurrent()
            assertEquals(other, assertIs<ScanState.Running>(controller.state.value).progress.root)
            assertEquals(ScanSessionRecord(other, 0), f.repository.currentScanSession())
            finishNext.complete(Unit)
            runCurrent()
            f.assertCompleted(controller, other, files = 2)
            assertEquals(listOf(root, other), f.invocations)
            assertEquals(DirectoryStatus.PENDING, f.repository.directory(root)!!.status)
        } finally {
            f.close()
        }
    }
}
