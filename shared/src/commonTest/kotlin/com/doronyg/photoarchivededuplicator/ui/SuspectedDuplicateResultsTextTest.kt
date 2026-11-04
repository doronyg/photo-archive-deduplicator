package com.doronyg.photoarchivededuplicator.ui

import com.doronyg.photoarchivededuplicator.exif.ExifInput
import com.doronyg.photoarchivededuplicator.exif.ExifMetadata
import com.doronyg.photoarchivededuplicator.exif.ExifReadResult
import com.doronyg.photoarchivededuplicator.exif.ExifRecord
import com.doronyg.photoarchivededuplicator.exif.ExifStatus
import com.doronyg.photoarchivededuplicator.recommendation.DuplicateRecommendationEngine
import com.doronyg.photoarchivededuplicator.recommendation.RecommendationAction
import com.doronyg.photoarchivededuplicator.retention.RetentionPresentation
import com.doronyg.photoarchivededuplicator.retention.RetentionRank
import com.doronyg.photoarchivededuplicator.retention.RetentionScorer
import com.doronyg.photoarchivededuplicator.scan.DuplicateSortCriterion
import com.doronyg.photoarchivededuplicator.scan.DuplicateSortRule
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import com.doronyg.photoarchivededuplicator.scan.SuspectedDuplicateFilter
import com.doronyg.photoarchivededuplicator.scan.SuspectedDuplicateGroup
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SuspectedDuplicateResultsTextTest {
    @Test
    fun sharesCompleteConfidenceExportWithRegularDuplicateResults() = runTest {
        val recommendation = confidencePresentationFixture()
        val files = recommendation.files.values.map { it.file }
        val report = formatSuspectedDuplicateResults(SuspectedDuplicateFilter(), emptyList(),
            listOf(SuspectedDuplicateGroup(files.first().sizeBytes, "2023:02:18 12:44:25", null, files)),
            StandardTestDispatcher(testScheduler), recommendations = files.associate { it.path to recommendation })
        assertTrue("Duplicate confidence: 100/100 (Very high)" in report)
        assertFalse("Retention-decision confidence:" in report.substringBefore("  Path:"))
        assertConfidencePresentationDetails(report, includesSummary = true)
        assertFileDecisionPresentationDetails(report)
        assertEquals(3, report.lineSequence().count { "Retention-decision confidence:" in it })
        val fileSections = report.substringAfter("  Path:")
        assertTrue(fileSections.indexOf(quoteResultText(files[1].path.toString())) <
            fileSections.indexOf(quoteResultText(files[2].path.toString())))
        assertFalse("100%" in report)
    }

    private val directory = "/Photos/2023-02-18".toPath()
    private val original = FileRecord(directory / "IMG_1234.jpg", directory, "IMG_1234.jpg", "jpg", 120_000, 1000, 1, 2000)
    private val copy = original.copy(path = directory / "IMG_1234-001.jpg", name = "IMG_1234-001.jpg")
    private val group = SuspectedDuplicateGroup(120_000, "2023:02:18 12:44:25", null, listOf(copy, original))
    private val exif = group.files.associate { it.path to ExifRecord(ExifInput.from(it),
        ExifReadResult(ExifStatus.EXTRACTED, ExifMetadata(capturedAt = group.capturedAt, make = "A\n\"B\"")), 3) }
    private val retention = group.files.associate {
        it.path to RetentionPresentation(RetentionScorer().score(it, 4, exif[it.path]), RetentionRank.CLOSE)
    }
    private val recommendation = DuplicateRecommendationEngine().recommend(group.files, exif, retention)

    @Test
    fun copiesDisplayedSnapshotWithFiltersFullPathsMetadataScoresAndRecommendations() = runTest {
        val report = formatSuspectedDuplicateResults(
            SuspectedDuplicateFilter("/Photos".toPath(), "IMG*.jpg", RecommendationAction.KEEP_ONE),
            listOf(DuplicateSortRule(DuplicateSortCriterion.RECOMMENDATION)), listOf(group),
            StandardTestDispatcher(testScheduler), retention, exif,
            group.files.associate { it.path to recommendation })
        for (text in listOf(
            "Suspected duplicates - not content-verified", "120000 bytes", "Copies: 2",
            "2023:02:18 12:44:25", "timezone unknown", "Folder filter: \"/Photos\"",
            "Wildcard (case-insensitive): \"IMG*.jpg\"", "Recommendation filter: KEEP_ONE",
            "RECOMMENDATION ASCENDING", "Group recommendation: KEEP_ONE", "File recommendation: REDUNDANT_CANDIDATE",
            "File recommendation: PREFERRED", "Normalized filename: \"IMG_1234.jpg\"",
            "Retention: 90", "directory-retention v6", "Created: 1970-01-01 00:00:02 UTC",
            "Modified: 1970-01-01 00:00:01 UTC", "Camera make: \"A\\n\\\"B\\\"\"",
            quoteResultText(copy.path.toString()), quoteResultText(original.path.toString()),
            "outside the folder filter", "No files have been changed",
        )) assertTrue(text in report, "Missing $text")
        assertTrue(report.indexOf("  Path: ${quoteResultText(copy.path.toString())}") <
            report.indexOf("  Path: ${quoteResultText(original.path.toString())}"),
            "Export must preserve displayed member order, not sort by score or filename again")
        assertFalse("Matched by exact case-sensitive filename" in report)
    }

    @Test
    fun emptyUnfilteredExportAndDisabledSortingAreExplicit() = runTest {
        val report = formatSuspectedDuplicateResults(SuspectedDuplicateFilter(), emptyList(), emptyList(),
            StandardTestDispatcher(testScheduler))
        assertTrue("Folder filter: all stored roots" in report)
        assertTrue("Recommendation filter: ALL" in report)
        assertTrue("Sort priority: none (search order)" in report)
        assertTrue("Candidate groups: 0" in report)
    }

    @Test
    fun groupOrderAndUnsafeTextArePreservedWithoutNewlinesCorruptingReportStructure() = runTest {
        val other = group.copy(capturedAt = "2024:01:01 00:00:00", offsetOriginal = "+01:00",
            files = listOf(original.copy(path = "/Other/\u96ea\n\"copy\".jpg".toPath())))
        val report = formatSuspectedDuplicateResults(SuspectedDuplicateFilter(filenamePattern = "\u96ea\n\"*"),
            emptyList(), listOf(other, group), StandardTestDispatcher(testScheduler))
        assertTrue(report.indexOf("2024:01:01") < report.indexOf("2023:02:18"))
        assertTrue(quoteResultText("/Other/\u96ea\n\"copy\".jpg") in report)
        assertTrue("Original offset: \"+01:00\"" in report)
        assertTrue("Retention: Not scored" in report)
    }

    @Test
    fun cancelledFormattingDoesNotPublishText() = runTest {
        var published: String? = null
        val work = async(start = CoroutineStart.UNDISPATCHED) {
            published = formatSuspectedDuplicateResults(SuspectedDuplicateFilter(), emptyList(), listOf(group),
                StandardTestDispatcher(testScheduler))
        }
        assertFalse(work.isCompleted)
        work.cancel()
        runCurrent()
        work.join()
        assertEquals(null, published)
    }
}
