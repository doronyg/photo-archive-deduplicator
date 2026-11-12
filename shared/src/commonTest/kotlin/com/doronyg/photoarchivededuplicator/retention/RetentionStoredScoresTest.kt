package com.doronyg.photoarchivededuplicator.retention

import com.doronyg.photoarchivededuplicator.exif.ExifInput
import com.doronyg.photoarchivededuplicator.exif.ExifMetadata
import com.doronyg.photoarchivededuplicator.exif.ExifReadResult
import com.doronyg.photoarchivededuplicator.exif.ExifRecord
import com.doronyg.photoarchivededuplicator.exif.ExifStatus
import com.doronyg.photoarchivededuplicator.scan.DirectoryListing
import com.doronyg.photoarchivededuplicator.scan.concurrencyTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RetentionStoredScoresTest {
    @Test
    fun extractionBarrierBlocksCompetingAssessmentsButAllowsStoredReads() = concurrencyTest { fixture ->
        for (folder in listOf("2013_06_25", "20130625")) {
            val directory = fixture.root / folder
            fixture.repository.seedRoot(directory)
            fixture.repository.recordListing(directory, DirectoryListing(emptyList(), listOf(
                DirectoryListing.Entry(directory / "photo.jpg", 12, null),
            )), 1)
        }
        val groups = fixture.repository.duplicateCandidates(fixture.root)
        var computations = 0L
        val service = RetentionScoreService(fixture.repository, dispatcher = fixture.dispatcher, clock = { ++computations })
        val initial = service.assess(groups)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val extraction = async {
            service.withoutAssessments { entered.complete(Unit); release.await() }
        }
        try {
            entered.await()
            val competing = async { service.assess(groups, force = true) }
            runCurrent()
            assertFalse(competing.isCompleted)
            assertEquals(initial, service.stored(groups))
            assertEquals(2L, computations)
            release.complete(Unit)
            extraction.await()
            competing.await()
            assertEquals(2L, computations)
            service.assess(groups, force = true)
            assertEquals(4L, computations)
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun cancellingExtractionDoesNotLetAnEarlierRequestAssessPartialExif() = concurrencyTest { fixture ->
        for (folder in listOf("2013_06_25", "20130625")) {
            val directory = fixture.root / folder
            fixture.repository.seedRoot(directory)
            fixture.repository.recordListing(directory, DirectoryListing(emptyList(), listOf(
                DirectoryListing.Entry(directory / "photo.jpg", 12, null),
            )), 1)
        }
        val groups = fixture.repository.duplicateCandidates(fixture.root)
        var computations = 0L
        val service = RetentionScoreService(fixture.repository, dispatcher = fixture.dispatcher, clock = { ++computations })
        service.assess(groups)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        // Enter extraction before the older browser request reaches its worker dispatcher.
        val overlapping = async(start = CoroutineStart.UNDISPATCHED) { service.assess(groups, force = true) }
        val extraction = async(fixture.dispatcher, start = CoroutineStart.UNDISPATCHED) {
            service.withoutAssessments { entered.complete(Unit); release.await() }
        }
        try {
            entered.await()
            val file = groups.single().files.first()
            fixture.repository.saveFileExif(ExifRecord(ExifInput.from(file),
                ExifReadResult(ExifStatus.EXTRACTED, ExifMetadata(capturedAt = "2013:06:25 12:00:00")), 2))
            runCurrent()
            assertFalse(overlapping.isCompleted)
            extraction.cancelAndJoin()
            assertEquals(RetentionRank.MISSING, overlapping.await().files.getValue(file.path).rank)
            assertEquals(2L, computations, "An old browser request must not reassess a cancelled partial queue")
            service.assess(groups)
            assertEquals(3L, computations, "A new explicit request may assess the persisted results")
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun storedReadsNeverComputeAndHideScoresInvalidatedDuringExifQueue() = concurrencyTest { fixture ->
        for (folder in listOf("2013_06_25", "20130625")) {
            val directory = fixture.root / folder
            fixture.repository.seedRoot(directory)
            fixture.repository.recordListing(directory, DirectoryListing(emptyList(), listOf(
                DirectoryListing.Entry(directory / "photo.jpg", 12, null),
            )), 1)
        }
        val groups = fixture.repository.duplicateCandidates(fixture.root)
        var computations = 0L
        val service = RetentionScoreService(fixture.repository, dispatcher = fixture.dispatcher, clock = { ++computations })
        assertTrue(service.stored(groups).files.values.all { it.rank == RetentionRank.MISSING })
        assertEquals(0L, computations)
        val before = service.assess(groups)
        assertEquals(before, service.stored(groups))
        val file = groups.single().files.first()
        fixture.repository.saveFileExif(ExifRecord(ExifInput.from(file),
            ExifReadResult(ExifStatus.EXTRACTED, ExifMetadata(capturedAt = "2013:06:25 12:00:00")), 2))
        fixture.fs.onBlockingCall = { error("Stored scores must not read sources") }
        val pending = service.stored(groups)
        assertEquals(RetentionRank.MISSING, pending.files.getValue(file.path).rank)
        assertTrue(pending.files.values.none { it.rank == RetentionRank.HIGHEST })
        assertEquals(2L, computations)
        assertTrue(fixture.repository.fileScores(groups.single().files.map { it.path }).all { it.value == 40.0 })
        val after = service.assess(groups)
        assertEquals(after, service.stored(groups))
        assertEquals(90.0, after.files.getValue(file.path).score!!.value)
        assertEquals(3L, computations)
    }
}
