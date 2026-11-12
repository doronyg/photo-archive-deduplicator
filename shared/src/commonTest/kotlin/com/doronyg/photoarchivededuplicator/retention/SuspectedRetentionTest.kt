package com.doronyg.photoarchivededuplicator.retention

import com.doronyg.photoarchivededuplicator.exif.ExifInput
import com.doronyg.photoarchivededuplicator.exif.ExifMetadata
import com.doronyg.photoarchivededuplicator.exif.ExifReadResult
import com.doronyg.photoarchivededuplicator.exif.ExifRecord
import com.doronyg.photoarchivededuplicator.exif.ExifStatus
import com.doronyg.photoarchivededuplicator.scan.ConcurrencyFixture
import com.doronyg.photoarchivededuplicator.scan.DirectoryListing
import com.doronyg.photoarchivededuplicator.scan.SuspectedDuplicateGroup
import com.doronyg.photoarchivededuplicator.scan.concurrencyTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SuspectedRetentionTest {
    @Test
    fun undatedSevenCopyExampleKeepsPlainCopiesTiedAndSharesScoresWithRegularDuplicates() = concurrencyTest { f ->
        val plain = "תמונה0006.jpg"
        val names = listOf(plain, "תמונה0006 (2020_09_13 05_22_30 UTC).jpg",
            "תמונה0006 (2021_12_30 01_53_40 UTC).jpg")
        for (folder in listOf("cell pics", "foreign picturea", "foreign pictures")) {
            val directory = f.root / folder
            f.repository.seedRoot(directory)
            f.repository.recordListing(directory, DirectoryListing(emptyList(),
                (if (folder == "foreign picturea") listOf(plain) else names).map {
                    DirectoryListing.Entry(directory / it, 100_863, null)
                }), 1)
            f.repository.filesIn(directory).forEach {
                f.repository.saveFileExif(ExifRecord(ExifInput.from(it), ExifReadResult(ExifStatus.EXTRACTED,
                    ExifMetadata(capturedAt = "2010:07:12 13:20:54")), 2))
            }
        }
        val group = f.repository.suspectedDuplicates().single()
        var computations = 0L
        val service = RetentionScoreService(f.repository, dispatcher = f.dispatcher, clock = { ++computations })
        val assessment = service.assessSuspected(listOf(group))
        val plainScores = group.files.filter { it.name == plain }.map { assessment.files.getValue(it.path) }
        assertEquals(3, plainScores.size)
        assertTrue(plainScores.all { it.score!!.value == 0.0 && it.rank == RetentionRank.TIED })
        assertTrue(group.files.filter { it.name != plain }.all {
            val score = assessment.files.getValue(it.path)
            score.score!!.value == -20.0 && score.rank == RetentionRank.LOWER
        })
        val regular = service.assess(f.repository.duplicateCandidates(f.root))
        assertEquals(assessment.files.mapValues { it.value.score }, regular.files.mapValues { it.value.score })
        assertEquals(7L, computations, "The same per-file score must be reusable across both kinds of group")
    }

    private suspend fun seed(f: ConcurrencyFixture): SuspectedDuplicateGroup {
        val date = "2019:07:16 11:16:19"
        val directory = f.root / "2019-07-16"
        f.repository.seedRoot(directory)
        f.repository.recordListing(directory, DirectoryListing(emptyList(), listOf(
            "DSC_4061.jpg", "DSC_4061-001.jpg", "DSC_4061 (2020_09_13 05_22_30 UTC).jpg",
        ).map { DirectoryListing.Entry(directory / it, 3_076_778, null) }), 1)
        f.repository.filesIn(directory).forEach {
            f.repository.saveFileExif(ExifRecord(ExifInput.from(it),
                ExifReadResult(ExifStatus.EXTRACTED, ExifMetadata(capturedAt = date)), 2))
        }
        return f.repository.suspectedDuplicates().single()
    }

    @Test
    fun scoringAndSortingCompareWholeSuspectedGroupAcrossDifferentNamesWithoutSourceReads() = concurrencyTest { f ->
        val group = seed(f)
        var computations = 0L
        val service = RetentionScoreService(f.repository, dispatcher = f.dispatcher, clock = { ++computations })
        f.fs.onBlockingCall = { error("Scoring must never reread source files") }
        assertTrue(service.storedSuspected(listOf(group)).files.values.all { it.rank == RetentionRank.MISSING })
        val scores = service.assessSuspected(listOf(group))
        val byName = group.files.associate { it.name to scores.files.getValue(it.path) }
        assertEquals(100.0, byName.getValue("DSC_4061.jpg").score!!.value)
        assertEquals(RetentionRank.CLOSE, byName.getValue("DSC_4061.jpg").rank)
        assertEquals(RetentionRank.CLOSE, byName.getValue("DSC_4061-001.jpg").rank)
        assertEquals(RetentionRank.LOWER, byName.getValue("DSC_4061 (2020_09_13 05_22_30 UTC).jpg").rank)
        val sorted = sortSuspectedFilesByRetention(listOf(group), scores.files, f.dispatcher).single()
        assertEquals(listOf("DSC_4061.jpg", "DSC_4061-001.jpg",
            "DSC_4061 (2020_09_13 05_22_30 UTC).jpg"), sorted.files.map { it.name })
        assertEquals(scores, service.storedSuspected(listOf(group)))
        service.assessSuspected(listOf(group))
        assertEquals(3L, computations)
        assertEquals(group.files.toSet(), sorted.files.toSet())
    }

    @Test
    fun changedCaptureTimeInvalidatesGroupRecommendationEvenWhenCalendarDateAndScoreStayUnchanged() = concurrencyTest { f ->
        val group = seed(f)
        val service = RetentionScoreService(f.repository, dispatcher = f.dispatcher)
        service.assessSuspected(listOf(group))
        val file = group.files.single { it.name == "DSC_4061.jpg" }
        f.repository.saveFileExif(ExifRecord(ExifInput.from(file), ExifReadResult(ExifStatus.EXTRACTED,
            ExifMetadata(capturedAt = "2019:07:16 12:00:00")), 3))
        val stored = service.storedSuspected(listOf(group))
        assertEquals(RetentionRank.MISSING, stored.files.getValue(file.path).rank)
        assertTrue(stored.files.values.none { it.rank == RetentionRank.HIGHEST })
        val assessed = service.assessSuspected(listOf(group))
        assertEquals(1, assessed.invalidatedFiles)
        assertEquals(RetentionRank.MISSING, assessed.files.getValue(file.path).rank)
    }

    @Test
    fun changedOffsetFailureOrReaderCannotLeaveAnOldGroupLeaderRecommendation() = concurrencyTest { f ->
        val group = seed(f)
        val service = RetentionScoreService(f.repository, dispatcher = f.dispatcher)
        service.assessSuspected(listOf(group))
        val file = group.files.single { it.name == "DSC_4061.jpg" }
        val original = f.repository.fileExif(listOf(file.path)).single()
        val changes = listOf(
            original.copy(result = original.result.copy(metadata = original.result.metadata.copy(offsetOriginal = "+01:00"))),
            original.copy(result = ExifReadResult(ExifStatus.ERROR, error = "Unreadable")),
            original.copy(readerVersion = "old"),
        )
        for (record in changes) {
            assertTrue(f.repository.saveFileExif(record))
            val stored = service.storedSuspected(listOf(group))
            assertEquals(RetentionRank.MISSING, stored.files.getValue(file.path).rank)
            assertTrue(stored.files.values.filter { it.score != null }.all { it.rank == RetentionRank.INCOMPLETE })
        }
    }

    @Test
    fun suspectedRequestsRespectExtractionBarrierAndDoNotRescoreCancelledPartialMetadata() = concurrencyTest { f ->
        val group = seed(f)
        var computations = 0L
        val service = RetentionScoreService(f.repository, dispatcher = f.dispatcher, clock = { ++computations })
        service.assessSuspected(listOf(group))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val extraction = async { service.withoutAssessments { entered.complete(Unit); release.await() } }
        try {
            entered.await()
            val pending = async { service.assessSuspected(listOf(group), force = true) }
            runCurrent()
            assertFalse(pending.isCompleted)
            assertEquals(3L, computations)
            assertEquals(3, service.storedSuspected(listOf(group)).files.size)
            extraction.cancelAndJoin()
            pending.await()
            assertEquals(3L, computations)
        } finally {
            release.complete(Unit)
        }
    }
}
