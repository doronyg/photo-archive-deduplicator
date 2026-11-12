package com.doronyg.photoarchivededuplicator.exif

import com.doronyg.photoarchivededuplicator.retention.RetentionConfig
import com.doronyg.photoarchivededuplicator.retention.RetentionScoreService
import com.doronyg.photoarchivededuplicator.retention.RetentionScorer
import com.doronyg.photoarchivededuplicator.scan.ConcurrencyFixture
import com.doronyg.photoarchivededuplicator.scan.DirectoryListing
import com.doronyg.photoarchivededuplicator.scan.concurrencyTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ExifQueueControllerTest {
    private suspend fun seed(fixture: ConcurrencyFixture) {
        for (folder in listOf("a/2007-06-06", "b/2008-07-07", "Temp")) {
            val directory = fixture.root / folder
            fixture.repository.seedRoot(directory)
            fixture.repository.recordListing(directory, DirectoryListing(emptyList(), listOf(
                DirectoryListing.Entry(directory / "first.jpg", 12, null),
                DirectoryListing.Entry(directory / "second.jpg", 13, null),
            )), 1)
        }
    }

    @Test
    fun initialScoringPrecedesEveryReadAndFinalScoringWaitsForTheEntireFrozenQueue() = concurrencyTest { fixture ->
        seed(fixture)
        val events = mutableListOf<String>()
        fixture.driver.beforeStatement = { sql ->
            if (sql.startsWith("INSERT") && "fileScore" in sql) events += "score"
        }
        val retention = RetentionScoreService(fixture.repository, dispatcher = fixture.dispatcher)
        val source = ExifSource {
            assertTrue(events.contains("score"), "Normal scoring must precede EXIF")
            events += "read"
            ExifReadResult(ExifStatus.EXTRACTED, ExifMetadata(capturedAt = "2001:01:01 00:00:00"))
        }
        val queue = ExifQueueController(fixture.repository, retention,
            ExifExtractionService(fixture.repository, source, fixture.dispatcher), this, fixture.dispatcher)
        assertTrue(queue.start(fixture.root))
        assertTrue(events.isEmpty(), "start must not execute caller-thread database/source work")
        assertFalse(queue.start(fixture.root))
        advanceUntilIdle()
        assertEquals(ExifQueuePhase.COMPLETED, queue.state.value.phase)
        assertEquals(6, events.count { it == "read" }, "Low-scoring members are still extracted")
        val firstRead = events.indexOf("read")
        val lastRead = events.lastIndexOf("read")
        assertTrue(events.subList(firstRead, lastRead + 1).all { it == "read" },
            "Neither each group nor each file can trigger reassessment")
        assertTrue(events.drop(lastRead + 1).contains("score"))
        assertEquals(2, queue.state.value.groupsCompleted)
        assertEquals(6, queue.state.value.filesCompleted)
        assertEquals(1, queue.state.value.revision)
    }

    @Test
    fun cancellationNeverReassessesPartialResultsOrPublishesLateProgressAndCanRestartFromCache() = concurrencyTest { fixture ->
        seed(fixture)
        val groups = fixture.repository.duplicateCandidates(fixture.root)
        val readingSecond = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        var reads = 0
        val retention = RetentionScoreService(fixture.repository, dispatcher = fixture.dispatcher)
        val queue = ExifQueueController(fixture.repository, retention, ExifExtractionService(
            fixture.repository, ExifSource {
                if (++reads == 2) {
                    readingSecond.complete(Unit)
                    hold.await()
                }
                ExifReadResult(ExifStatus.NO_EXIF)
            }, fixture.dispatcher,
        ), this, fixture.dispatcher)
        assertTrue(queue.start(groups))
        readingSecond.await()
        val scoresBeforeCancel = fixture.repository.fileScores(groups.flatMap { it.files }.map { it.path })
        queue.cancel()
        val cancelled = queue.state.value
        assertEquals(ExifQueuePhase.CANCELLED, cancelled.phase)
        assertEquals(1, cancelled.filesCompleted)
        assertEquals(1, cancelled.revision, "Cancellation refreshes cached EXIF without reassessing scores")
        hold.complete(Unit)
        advanceUntilIdle()
        assertEquals(cancelled, queue.state.value)
        assertEquals(scoresBeforeCancel, fixture.repository.fileScores(groups.flatMap { it.files }.map { it.path }))
        assertTrue(queue.start(groups))
        advanceUntilIdle()
        assertEquals(ExifQueuePhase.COMPLETED, queue.state.value.phase)
        assertEquals(7, reads, "The completed first file is reused, the interrupted file is retried")
    }

    @Test
    fun cachedFailureQueueStillReportsAllVisitedFilesWithoutReopeningSources() = concurrencyTest { fixture ->
        seed(fixture)
        var reads = 0
        val queue = ExifQueueController(fixture.repository,
            RetentionScoreService(fixture.repository, dispatcher = fixture.dispatcher),
            ExifExtractionService(fixture.repository, ExifSource {
                reads++
                ExifReadResult(ExifStatus.ERROR, error = "Unreadable")
            }, fixture.dispatcher), this, fixture.dispatcher)
        queue.start(fixture.root)
        advanceUntilIdle()
        assertEquals(6, reads)
        queue.start(fixture.root)
        advanceUntilIdle()
        assertEquals(6, reads)
        assertEquals(6, queue.state.value.filesCompleted)
        assertEquals(2, queue.state.value.revision)
    }

    @Test
    fun unrelatedAssessmentsWaitUntilAllQueuedGroupsHaveFinished() = concurrencyTest { fixture ->
        seed(fixture)
        val groups = fixture.repository.duplicateCandidates(fixture.root)
        val reading = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        var reads = 0
        val retention = RetentionScoreService(fixture.repository, dispatcher = fixture.dispatcher)
        val queue = ExifQueueController(fixture.repository, retention, ExifExtractionService(
            fixture.repository, ExifSource {
                if (++reads == 1) {
                    reading.complete(Unit)
                    hold.await()
                }
                ExifReadResult(ExifStatus.NO_EXIF)
            }, fixture.dispatcher,
        ), this, fixture.dispatcher)
        queue.start(groups)
        reading.await()
        val concurrentAssessment = async { retention.assess(groups, force = true) }
        runCurrent()
        assertFalse(concurrentAssessment.isCompleted)
        hold.complete(Unit)
        advanceUntilIdle()
        assertEquals(6, reads)
        assertTrue(concurrentAssessment.isCompleted)
        assertEquals(ExifQueuePhase.COMPLETED, queue.state.value.phase)
    }

    @Test
    fun databaseErrorsFailTheQueueAndExposeDiagnosticsWithATerminalRefreshRevision() = concurrencyTest { fixture ->
        seed(fixture)
        fixture.driver.beforeStatement = { error("Database unavailable") }
        val queue = ExifQueueController(fixture.repository,
            RetentionScoreService(fixture.repository, dispatcher = fixture.dispatcher),
            ExifExtractionService(fixture.repository, ExifSource { error("Must not read") }, fixture.dispatcher),
            this, fixture.dispatcher)
        queue.start(fixture.root)
        advanceUntilIdle()
        assertEquals(ExifQueuePhase.FAILED, queue.state.value.phase)
        assertTrue(queue.state.value.error.orEmpty().contains("Database unavailable"))
        assertEquals(1, queue.state.value.revision)
        fixture.driver.beforeStatement = {}
    }

    @Test
    fun scanningBlocksManualStartsButAStoppedOrPausedScanDoesNot() = concurrencyTest { fixture ->
        seed(fixture)
        val reading = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        val queue = ExifQueueController(fixture.repository,
            RetentionScoreService(fixture.repository, dispatcher = fixture.dispatcher),
            ExifExtractionService(fixture.repository, ExifSource {
                reading.complete(Unit)
                hold.await()
                ExifReadResult(ExifStatus.NO_EXIF)
            }, fixture.dispatcher), this, fixture.dispatcher)
        queue.start(fixture.root)
        reading.await()
        queue.scanStarting()
        assertEquals(ExifQueuePhase.CANCELLED, queue.state.value.phase)
        assertFalse(queue.start(fixture.root))
        queue.scanStopped()
        hold.complete(Unit)
        assertTrue(queue.start(fixture.root), "Explicit manual work is allowed after Pause")
        advanceUntilIdle()
        assertEquals(ExifQueuePhase.COMPLETED, queue.state.value.phase)
        queue.scanAccepted()
        queue.scanStarting()
        assertFalse(queue.start(fixture.root))
        assertTrue(queue.scanCompleted(fixture.root), "Genuine completion claims the automatic queue before manual work")
        advanceUntilIdle()
        assertEquals(ExifQueuePhase.COMPLETED, queue.state.value.phase)
        assertEquals(3, queue.state.value.revision)
        queue.shutdown()
        queue.scanStopped()
        assertFalse(queue.start(fixture.root))
        assertFalse(queue.start(emptyList()))
        assertFalse(queue.scanCompleted(fixture.root), "A late scan completion cannot reopen a shut-down queue")
    }

    @Test
    fun cancellingBeforeTheFirstDispatchReleasesTheRunAndDoesNoDatabaseWork() = concurrencyTest { fixture ->
        seed(fixture)
        var statements = 0
        fixture.driver.beforeStatement = { statements++ }
        val queue = ExifQueueController(fixture.repository,
            RetentionScoreService(fixture.repository, dispatcher = fixture.dispatcher),
            ExifExtractionService(fixture.repository, ExifSource { error("Must not read") }, fixture.dispatcher),
            this, fixture.dispatcher)
        assertTrue(queue.start(fixture.root))
        queue.cancel()
        assertEquals(ExifQueuePhase.CANCELLED, queue.state.value.phase)
        assertEquals(0, statements)
        assertTrue(queue.start(emptyList()))
        advanceUntilIdle()
        assertEquals(ExifQueuePhase.COMPLETED, queue.state.value.phase)
        assertEquals(0, queue.state.value.groupsTotal)
        statements = 0
        assertTrue(queue.start(fixture.root))
        queue.scanStarting()
        assertEquals(ExifQueuePhase.CANCELLED, queue.state.value.phase)
        assertEquals(0, statements, "Scan start cancels an accepted queue before its first dispatch")
        assertFalse(queue.start(fixture.root))
        queue.scanStopped()
    }

    @Test
    fun applicationScopeCancellationCancelsTheQueueAndRejectsFutureWork() = concurrencyTest { fixture ->
        seed(fixture)
        val owner = SupervisorJob()
        val queue = ExifQueueController(fixture.repository,
            RetentionScoreService(fixture.repository, dispatcher = fixture.dispatcher),
            ExifExtractionService(fixture.repository, ExifSource { error("Must not read") }, fixture.dispatcher),
            CoroutineScope(owner + fixture.dispatcher), fixture.dispatcher)
        assertTrue(queue.start(fixture.root))
        owner.cancelAndJoin()
        assertEquals(ExifQueuePhase.CANCELLED, queue.state.value.phase)
        assertFalse(queue.start(fixture.root))
    }

    @Test
    fun noEligibleGroupsCompleteWithoutExtractionOrADuplicateAssessment() = concurrencyTest { fixture ->
        for (folder in listOf("2007", "2008/07")) {
            val directory = fixture.root / folder
            fixture.repository.seedRoot(directory)
            fixture.repository.recordListing(directory, DirectoryListing(emptyList(), listOf(
                DirectoryListing.Entry(directory / "photo.jpg", 12, null),
            )), 1)
        }
        var scoreQueries = 0
        fixture.driver.beforeStatement = { sql ->
            if (sql.startsWith("SELECT") && "FROM fileScore" in sql && "filePath IN" in sql) scoreQueries++
        }
        val queue = ExifQueueController(fixture.repository,
            RetentionScoreService(fixture.repository, dispatcher = fixture.dispatcher),
            ExifExtractionService(fixture.repository, ExifSource { error("Partial dates must not read") }, fixture.dispatcher),
            this, fixture.dispatcher)
        queue.start(fixture.root)
        advanceUntilIdle()
        assertEquals(ExifQueuePhase.COMPLETED, queue.state.value.phase)
        assertEquals(0, queue.state.value.groupsTotal)
        assertEquals(1, scoreQueries)
    }

    @Test
    fun manualDisplayedSubsetUsesItsCurrentConfiguredScoresAndNeverFetchesHiddenGroups() = concurrencyTest { fixture ->
        seed(fixture)
        val displayed = fixture.repository.duplicateCandidates(fixture.root).take(1)
        val readPaths = mutableListOf<String>()
        val queue = ExifQueueController(fixture.repository,
            RetentionScoreService(fixture.repository, RetentionScorer(RetentionConfig(isoDateBonus = 110.0)),
                dispatcher = fixture.dispatcher),
            ExifExtractionService(fixture.repository, ExifSource {
                readPaths += it.path.toString()
                ExifReadResult(ExifStatus.NO_EXIF)
            }, fixture.dispatcher), this, fixture.dispatcher)
        queue.scanAccepted()
        assertFalse(queue.start(displayed), "Accepted scans block manual work before their first dispatch")
        queue.scanStopped()
        assertTrue(queue.start(displayed))
        advanceUntilIdle()
        assertEquals(ExifQueuePhase.COMPLETED, queue.state.value.phase)
        assertEquals(1, queue.state.value.groupsTotal)
        assertEquals(displayed.single().files.map { it.path.toString() }, readPaths)
    }

    @Test
    fun partialFailureRefreshesCachedExifAndHidesInvalidScoresWithoutReassessment() = concurrencyTest { fixture ->
        seed(fixture)
        val group = fixture.repository.duplicateCandidates(fixture.root).first().let {
            it.copy(files = it.files.sortedBy { file -> file.directoryPath.toString().contains("2007").not() })
        }
        val first = group.files.first()
        var reads = 0
        val retention = RetentionScoreService(fixture.repository, dispatcher = fixture.dispatcher)
        val queue = ExifQueueController(fixture.repository, retention,
            ExifExtractionService(fixture.repository, ExifSource {
                if (++reads == 2) error("Database connection lost")
                ExifReadResult(ExifStatus.EXTRACTED, ExifMetadata(capturedAt = "2001:01:01 00:00:00"))
            }, fixture.dispatcher), this, fixture.dispatcher)
        queue.start(listOf(group))
        advanceUntilIdle()
        assertEquals(ExifQueuePhase.FAILED, queue.state.value.phase)
        assertEquals(1, queue.state.value.filesCompleted)
        assertEquals(1, queue.state.value.revision)
        assertEquals(1, fixture.repository.fileExif(group.files.map { it.path }).size)
        assertEquals(100.0, fixture.repository.fileScores(listOf(first.path)).single().value,
            "A failed partial queue must not reassess its newly cached EXIF")
        assertEquals(null, retention.stored(listOf(group)).files.getValue(first.path).score,
            "A revision-triggered DB-only refresh hides scores invalidated by partial EXIF")
    }
}
