package com.doronyg.photoarchivededuplicator.exif

import com.doronyg.photoarchivededuplicator.retention.RetentionScoreService
import com.doronyg.photoarchivededuplicator.scan.ConcurrencyFixture
import com.doronyg.photoarchivededuplicator.scan.DirectoryListing
import com.doronyg.photoarchivededuplicator.scan.concurrencyTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SuspectedExifQueueTest {
    private suspend fun seed(fixture: ConcurrencyFixture) {
        for ((folder, name) in listOf(
            "2024-01-02" to "original.jpg",
            "copies" to "original.jpg",
            "Temp" to "renamed.jpg",
        )) {
            val directory = fixture.root / folder
            fixture.repository.seedRoot(directory)
            fixture.repository.recordListing(directory, DirectoryListing(emptyList(), listOf(
                DirectoryListing.Entry(directory / name, 100_001, null),
                DirectoryListing.Entry(directory / "regular-only.jpg", 200_001, null),
            )), 1)
        }
    }

    @Test
    fun explicitAnalysisVisitsWholeMixedNameBucketRegardlessOfScoreGapOrDatedFolders() = concurrencyTest { fixture ->
        seed(fixture)
        val reads = mutableListOf<String>()
        fixture.driver.beforeStatement = { sql ->
            if (sql.startsWith("INSERT") && "fileScore" in sql) {
                assertEquals(3, reads.size, "Scores may only be updated after all EXIF files finish")
            }
        }
        val queue = ExifQueueController(fixture.repository,
            RetentionScoreService(fixture.repository, dispatcher = fixture.dispatcher),
            ExifExtractionService(fixture.repository, ExifSource {
                reads += it.name
                ExifReadResult(ExifStatus.EXTRACTED, ExifMetadata(capturedAt = "2024:01:02 12:34:56"))
            }, fixture.dispatcher), this, fixture.dispatcher)
        assertTrue(queue.startSuspected())
        assertTrue(reads.isEmpty(), "Manual start must not read sources on the caller thread")
        advanceUntilIdle()
        assertEquals(ExifQueuePurpose.SUSPECTED_DUPLICATES, queue.state.value.purpose)
        assertEquals(ExifQueuePhase.COMPLETED, queue.state.value.phase)
        assertEquals(listOf("original.jpg", "original.jpg", "renamed.jpg").sorted(), reads.sorted())
        assertEquals(1, queue.state.value.groupsCompleted)
        assertEquals(3, queue.state.value.filesCompleted)
        val results = fixture.repository.suspectedDuplicates()
        assertEquals(3, results.single().files.size)
        assertTrue(queue.startSuspected())
        advanceUntilIdle()
        assertEquals(3, reads.size, "Repeated analysis must reuse all unchanged EXIF")
    }

    @Test
    fun manualSuspectedAnalysisSharesScanGatesOverlapCancellationAndCacheWithRegularQueue() = concurrencyTest { fixture ->
        seed(fixture)
        val readingSecond = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        var reads = 0
        val queue = ExifQueueController(fixture.repository,
            RetentionScoreService(fixture.repository, dispatcher = fixture.dispatcher),
            ExifExtractionService(fixture.repository, ExifSource {
                if (++reads == 2) {
                    readingSecond.complete(Unit)
                    hold.await()
                }
                ExifReadResult(ExifStatus.EXTRACTED, ExifMetadata(capturedAt = "2024:01:02 12:34:56"))
            }, fixture.dispatcher), this, fixture.dispatcher)
        queue.scanAccepted()
        assertFalse(queue.startSuspected())
        queue.scanStopped()
        assertTrue(queue.startSuspected())
        assertFalse(queue.start(fixture.root))
        assertFalse(queue.startSuspected())
        readingSecond.await()
        queue.scanStarting()
        assertEquals(ExifQueuePhase.CANCELLED, queue.state.value.phase)
        assertEquals(1, queue.state.value.filesCompleted)
        assertTrue(fixture.repository.fileScores(fixture.repository.allFiles().map { it.path }).isEmpty())
        assertFalse(queue.startSuspected())
        queue.scanStopped()
        hold.complete(Unit)
        assertTrue(queue.startSuspected())
        advanceUntilIdle()
        assertEquals(4, reads, "Completed EXIF is reused; interrupted and unvisited files are read")
        assertEquals(ExifQueuePhase.COMPLETED, queue.state.value.phase)
        queue.shutdown()
        queue.scanStopped()
        assertFalse(queue.startSuspected())
    }

    @Test
    fun sameNameOnlyBucketsProduceNoExifReadsForSuspectedAnalysis() = concurrencyTest { fixture ->
        for (folder in listOf("a", "b")) {
            val directory = fixture.root / folder
            fixture.repository.seedRoot(directory)
            fixture.repository.recordListing(directory, DirectoryListing(emptyList(), listOf(
                DirectoryListing.Entry(directory / "same.jpg", 100_001, null),
            )), 1)
        }
        val queue = ExifQueueController(fixture.repository,
            RetentionScoreService(fixture.repository, dispatcher = fixture.dispatcher),
            ExifExtractionService(fixture.repository, ExifSource { error("Regular-only buckets must not read") },
                fixture.dispatcher), this, fixture.dispatcher)
        assertTrue(queue.startSuspected())
        advanceUntilIdle()
        assertEquals(ExifQueuePhase.COMPLETED, queue.state.value.phase)
        assertEquals(0, queue.state.value.filesTotal)
        assertTrue(fixture.repository.fileExif(fixture.repository.allFiles().map { it.path }).isEmpty())
    }
}
