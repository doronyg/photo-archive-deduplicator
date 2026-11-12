package com.doronyg.photoarchivededuplicator.recommendation

import com.doronyg.photoarchivededuplicator.exif.ExifInput
import com.doronyg.photoarchivededuplicator.exif.ExifMetadata
import com.doronyg.photoarchivededuplicator.exif.ExifReadResult
import com.doronyg.photoarchivededuplicator.exif.ExifRecord
import com.doronyg.photoarchivededuplicator.exif.ExifStatus
import com.doronyg.photoarchivededuplicator.retention.RetentionScoreService
import com.doronyg.photoarchivededuplicator.scan.DirectoryListing
import com.doronyg.photoarchivededuplicator.scan.concurrencyTest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DuplicateRecommendationsTest {
    @Test
    fun storedSuspectedResultsCanBeRecommendedWithoutAnyFilesystemAccessOrWrites() = concurrencyTest { f ->
        val directory = f.root / "2023-02-18"
        f.repository.seedRoot(directory)
        f.repository.recordListing(directory, DirectoryListing(emptyList(), listOf(
            "20230218_124425.jpg", "20230218_124425-001.jpg",
        ).map { DirectoryListing.Entry(directory / it, 120_000, 1, 2) }), 3)
        f.repository.filesIn(directory).forEach {
            f.repository.saveFileExif(ExifRecord(ExifInput.from(it),
                ExifReadResult(ExifStatus.EXTRACTED, ExifMetadata(capturedAt = "2023:02:18 12:44:25")), 4))
        }
        val groups = f.repository.suspectedDuplicates()
        val service = RetentionScoreService(f.repository, dispatcher = f.dispatcher)
        val scores = service.assessSuspected(groups)
        val files = groups.single().files
        val beforeExif = f.repository.fileExif(files.map { it.path })
        val beforeInventory = f.repository.filesIn(directory)
        f.fs.onBlockingCall = { error("Recommendations must not touch source files") }
        val recommendations = recommendDuplicateGroups(groups.map { it.files },
            beforeExif.associateBy { it.input.path }, scores.files, dispatcher = f.dispatcher)
        assertEquals(files.map { it.path }.toSet(), recommendations.keys)
        assertTrue(recommendations.values.all { it == recommendations.values.first() })
        assertEquals(RecommendationAction.KEEP_ONE, recommendations.values.first().action)
        assertEquals(beforeExif, f.repository.fileExif(files.map { it.path }))
        assertEquals(beforeInventory, f.repository.filesIn(directory))
        assertEquals(scores, service.storedSuspected(groups))
        val protected = recommendDuplicateGroups(groups.map { it.files },
            beforeExif.associateBy { it.input.path }, scores.files, dispatcher = f.dispatcher,
            protectedPaths = setOf(files.last().path))
        assertTrue(protected.values.all { it.action == RecommendationAction.PROTECTED && it.redundantCandidates.isEmpty() })
        assertEquals(beforeExif, f.repository.fileExif(files.map { it.path }))
        assertEquals(scores, service.storedSuspected(groups))
        val pending = recommendDuplicateGroups(groups.map { it.files },
            beforeExif.associateBy { it.input.path }, scores.files, evidenceReady = false, dispatcher = f.dispatcher)
        assertTrue(pending.values.all { it.action == RecommendationAction.REVIEW && it.redundantCandidates.isEmpty() })
    }

    @Test
    fun cancelledBackgroundCalculationDoesNotPublishRecommendations() = runTest {
        var published = false
        val calculation = launch(start = CoroutineStart.UNDISPATCHED) {
            recommendDuplicateGroups(emptyList(), emptyMap(), emptyMap(),
                dispatcher = StandardTestDispatcher(testScheduler))
            published = true
        }
        assertFalse(published)
        calculation.cancel()
        runCurrent()
        calculation.join()
        assertFalse(published)
    }
}
