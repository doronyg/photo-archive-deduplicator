package com.doronyg.photoarchivededuplicator.scan

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ScannerConcurrencyTest {
    @Test
    fun secondDirectoryScannerWaitsBeforeSeedingAndCancelledWaiterDoesNotMutate() = concurrencyTest { f ->
        f.write(f.root / "a.jpg")
        val other = f.root.parent!! / "other"
        f.write(other / "b.jpg")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = launch {
            f.scanner().startScan(f.root) {
                if (it is ScanEvent.DirectoryStarted) {
                    entered.complete(Unit)
                    release.await()
                }
            }
        }
        entered.await()
        val before = f.repository.allDirectories()
        val count = f.driver.statements.size
        val secondScanner = f.scanner()
        val second = launch { secondScanner.startScan(other) }
        runCurrent()
        assertFalse(second.isCompleted)
        assertEquals(count, f.driver.statements.size)
        assertTrue(f.fs.listCalls.isEmpty())
        second.cancelAndJoin()
        assertEquals(before, f.repository.allDirectories())
        assertNull(f.repository.directory(other))
        release.complete(Unit)
        first.join()
        secondScanner.startScan(other)
        assertEquals(listOf(f.root, other), f.fs.listCalls)
        assertEquals(setOf(f.root / "a.jpg", other / "b.jpg"), f.repository.allFiles().map { it.path }.toSet())
    }

    @Test
    fun mediaScannerCannotReadSourceOrSeedWhileDirectoryScannerOwnsSession() = concurrencyTest { f ->
        f.write(f.root / "a.jpg")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = launch {
            f.scanner().startScan(f.root) {
                if (it is ScanEvent.DirectoryStarted) {
                    entered.complete(Unit)
                    release.await()
                }
            }
        }
        entered.await()
        var sourceCalls = 0
        val other = f.root.parent!! / "media"
        val media = MediaIndexScanner(MediaSource {
            sourceCalls++
            listOf(MediaItem(other / "b.jpg", 2, 3))
        }, f.repository, f.dispatcher)
        val count = f.driver.statements.size
        val second = launch { media.startScan(other) }
        runCurrent()
        assertFalse(second.isCompleted)
        assertEquals(0, sourceCalls)
        assertEquals(count, f.driver.statements.size)
        release.complete(Unit)
        first.join()
        second.join()
        assertEquals(1, sourceCalls)
        assertEquals(DirectoryStatus.COMPLETED, f.repository.directory(other)?.status)
        assertEquals(listOf(f.root), f.fs.listCalls)
    }

    @Test
    fun addAndRemoveExclusionsAreRejectedAtStartedAndCommittedEventGates() = concurrencyTest { f ->
        f.write(f.root / "a.jpg")
        val excluded = f.root / "private"
        f.repository.addExclusion(excluded, "keep")
        val started = CompletableDeferred<Unit>()
        val continueStarted = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Unit>()
        val continueCompleted = CompletableDeferred<Unit>()
        val job = launch {
            f.scanner().startScan(f.root) {
                when (it) {
                    is ScanEvent.DirectoryStarted -> {
                        started.complete(Unit)
                        continueStarted.await()
                    }
                    is ScanEvent.DirectoryCompleted -> {
                        completed.complete(Unit)
                        continueCompleted.await()
                    }
                    else -> Unit
                }
            }
        }
        suspend fun assertEditsRejected() {
            assertFailsWith<IllegalStateException> { f.repository.addExclusion(f.root / "new") }
            assertFailsWith<IllegalStateException> { f.repository.removeExclusion(excluded) }
            assertEquals(listOf(DirectoryExclusion(excluded, "keep")), f.repository.exclusions())
        }
        started.await()
        assertEditsRejected()
        continueStarted.complete(Unit)
        completed.await()
        assertEquals(DirectoryStatus.COMPLETED, f.repository.directory(f.root)?.status)
        assertEditsRejected()
        continueCompleted.complete(Unit)
        job.join()
        f.repository.removeExclusion(excluded)
        f.repository.addExclusion(f.root / "new")
        assertEquals(listOf(f.root / "new"), f.repository.exclusions().map { it.path })
    }

    @Test
    fun cancellationDuringMetadataDiscardsPartialListingAndResumeConverges() = concurrencyTest { f ->
        f.write(f.root / "a.jpg", "old")
        f.scanner().startScan(f.root)
        val oldFiles = f.repository.allFiles()
        f.write(f.root / "a.jpg", "changed")
        f.write(f.root / "b.jpg", "second")
        f.write(f.root / "c.jpg", "third")
        lateinit var job: Job
        var cancelledAtMetadata = false
        val guard = f.fs.onBlockingCall
        f.fs.onBlockingCall = {
            guard(it)
            if (it == "metadataOrNull(${f.root / "b.jpg"})") {
                cancelledAtMetadata = true
                job.cancel()
            }
        }
        job = launch { f.scanner().startScan(f.root) }
        job.join()
        assertTrue(cancelledAtMetadata)
        assertTrue(job.isCancelled)
        assertEquals(oldFiles, f.repository.allFiles())
        val interrupted = f.repository.directory(f.root)!!
        assertEquals(DirectoryStatus.IN_PROGRESS, interrupted.status)
        assertEquals(0, interrupted.retryCount)
        f.fs.onBlockingCall = guard
        val callsBefore = f.fs.listCalls.size
        f.scanner().resume()
        assertEquals(listOf(f.root), f.fs.listCalls.drop(callsBefore))
        val actual = f.repository.allFiles()
        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), actual.map { it.name })
        for (file in actual) {
            val metadata = f.fakeFs.metadata(file.path)
            assertEquals(metadata.size, file.sizeBytes)
            assertEquals(metadata.createdAtMillis, file.createdAt)
            assertEquals(metadata.lastModifiedAtMillis, file.modifiedAt)
            assertEquals(900L, file.lastScanned)
        }
        assertEquals(DirectoryStatus.COMPLETED, f.repository.directory(f.root)?.status)
    }

    @Test
    fun cancellationInCompletedCallbackResumesChildrenWithoutRescanningRootOrOtherInventory() = concurrencyTest { f ->
        f.write(f.root / "child" / "a.jpg")
        val other = f.root.parent!! / "other"
        f.repository.seedRoot(other)
        f.repository.markInProgress(other)
        f.repository.beginScanSession(f.root)
        val otherBefore = f.repository.directory(other)
        val committed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job = launch {
            f.scanner().startScan(f.root) {
                if (it is ScanEvent.DirectoryCompleted && it.path == f.root) {
                    committed.complete(Unit)
                    release.await()
                }
            }
        }
        committed.await()
        val rootBefore = f.repository.directory(f.root)
        assertEquals(DirectoryStatus.COMPLETED, rootBefore?.status)
        assertEquals(DirectoryStatus.PENDING, f.repository.directory(f.root / "child")?.status)
        job.cancelAndJoin()
        f.fs.listCalls.clear()
        f.scanner().resume()
        assertEquals(listOf(f.root / "child"), f.fs.listCalls)
        assertEquals(rootBefore, f.repository.directory(f.root))
        assertEquals(otherBefore, f.repository.directory(other))
        assertEquals(listOf(f.root / "child" / "a.jpg"), f.repository.allFiles().map { it.path })
    }

    @Test
    fun mediaSourceExceptionPropagatesWithoutFilesystemRetriesAndResumeRecovers() = concurrencyTest { f ->
        val sentinel = IOException("media source unavailable")
        var fail = true
        var calls = 0
        val scanner = MediaIndexScanner(MediaSource {
            calls++
            if (fail) throw sentinel
            listOf(MediaItem(f.root / "a.jpg", 7, 8))
        }, f.repository, f.dispatcher)
        val failure = async {
            assertFailsWith<IOException> { scanner.startScan(f.root) }
        }
        assertEquals(sentinel.message, failure.await().message)
        assertEquals(1, calls)
        assertTrue(f.fs.listCalls.isEmpty())
        assertTrue(f.repository.allFiles().isEmpty())
        assertEquals(0, f.repository.directory(f.root)?.retryCount)
        fail = false
        scanner.resume()
        assertEquals(2, calls)
        assertEquals(7L, f.repository.file(f.root / "a.jpg")?.sizeBytes)
        assertEquals(DirectoryStatus.COMPLETED, f.repository.directory(f.root)?.status)
    }

    @Test
    fun cancellingRetryBackoffPreservesAttemptCountAndResumeRecovers() = concurrencyTest { f ->
        f.write(f.root / "a.jpg")
        f.fs.failListing(f.root, times = 2)
        val failed = CompletableDeferred<Unit>()
        val config = ScanConfig(initialBackoff = 1.seconds)
        val startTime = testScheduler.currentTime
        val job = launch {
            f.scanner(config).startScan(f.root) {
                if (it is ScanEvent.DirectoryFailed) failed.complete(Unit)
            }
        }
        failed.await()
        runCurrent()
        assertEquals(startTime, testScheduler.currentTime)
        assertEquals(1, f.fs.listCalls.size)
        assertEquals(1, f.repository.directory(f.root)?.retryCount)
        job.cancelAndJoin()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, f.fs.listCalls.size)
        assertEquals(DirectoryStatus.PENDING, f.repository.directory(f.root)?.status)
        val retryCounts = mutableListOf<Int>()
        val resumeTime = testScheduler.currentTime
        val resumed = launch {
            f.scanner(config).resume {
                if (it is ScanEvent.DirectoryFailed) retryCounts += it.attempts
            }
        }
        runCurrent()
        assertEquals(listOf(2), retryCounts)
        assertEquals(2, f.repository.directory(f.root)?.retryCount)
        advanceUntilIdle()
        resumed.join()
        assertEquals(3, f.fs.listCalls.size)
        assertEquals(DirectoryStatus.COMPLETED, f.repository.directory(f.root)?.status)
        assertEquals(0, f.repository.directory(f.root)?.retryCount)
        assertEquals(5L, f.repository.file(f.root / "a.jpg")?.sizeBytes)
        assertEquals(2_000L, testScheduler.currentTime - resumeTime)
    }
}
