package com.doronyg.photoarchivededuplicator.ui

import com.doronyg.photoarchivededuplicator.exif.ExifInput
import com.doronyg.photoarchivededuplicator.exif.ExifMetadata
import com.doronyg.photoarchivededuplicator.exif.ExifReadResult
import com.doronyg.photoarchivededuplicator.exif.ExifRecord
import com.doronyg.photoarchivededuplicator.exif.ExifStatus
import com.doronyg.photoarchivededuplicator.retention.RetentionPresentation
import com.doronyg.photoarchivededuplicator.retention.RetentionRank
import com.doronyg.photoarchivededuplicator.retention.RetentionScorer
import com.doronyg.photoarchivededuplicator.retention.ScoreFactor
import com.doronyg.photoarchivededuplicator.retention.sortDuplicateFilesByRetention
import com.doronyg.photoarchivededuplicator.scan.DuplicateCandidateFilter
import com.doronyg.photoarchivededuplicator.scan.DuplicateCandidateGroup
import com.doronyg.photoarchivededuplicator.scan.DuplicateFileType
import com.doronyg.photoarchivededuplicator.scan.DuplicateSortCriterion
import com.doronyg.photoarchivededuplicator.scan.DuplicateSortRule
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import com.doronyg.photoarchivededuplicator.scan.SortDirection
import com.doronyg.photoarchivededuplicator.scan.TrackingIoDispatcher
import com.doronyg.photoarchivededuplicator.scan.defaultDuplicateSortRules
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class DuplicateResultsTextTest {
    @Test
    fun exportsIndependentConfidenceScoresAndCompleteEscapedComparisonEvidence() = runTest {
        val recommendation = confidencePresentationFixture()
        val files = recommendation.files.values.map { it.file }
        val report = formatDuplicateResults("/Pictures", DuplicateCandidateFilter(), emptyList(),
            listOf(DuplicateCandidateGroup("image.jpg", files.first().sizeBytes, files)),
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

    @Test
    fun exifReportQuotesRawFieldsAndDoesNotChangeRetentionOrOrder() = runTest(timeout = 10.seconds) {
        val group = group("IMG.jpg")
        val file = group.files.first()
        val scores = mapOf(file.path to RetentionPresentation(RetentionScorer().score(file, 1000), RetentionRank.HIGHEST))
        val worker = StandardTestDispatcher(testScheduler)
        val plain = formatDuplicateResults("/archive", DuplicateCandidateFilter(), defaultDuplicateSortRules(),
            listOf(group), worker, retention = scores)
        val record = ExifRecord(ExifInput.from(file), ExifReadResult(ExifStatus.EXTRACTED,
            ExifMetadata(capturedAt = "2024:07:08 09:10:11", make = "A\n\"B\"")), 1000)
        val report = formatDuplicateResults("/archive", DuplicateCandidateFilter(), defaultDuplicateSortRules(),
            listOf(group), worker, retention = scores, exif = mapOf(file.path to record))
        assertTrue("Original capture datetime: \"2024:07:08 09:10:11\"" in report)
        assertTrue("Original offset: \"unknown (timezone unknown)\"" in report)
        assertTrue("Camera make: \"A\\n\\\"B\\\"\"" in report)
        assertTrue("EXIF: Not extracted" in report)
        assertEquals(plain.lineSequence().filter { "Retention" in it || "Path:" in it }.toList(),
            report.lineSequence().filter { "Retention" in it || "Path:" in it }.toList())
    }

    @Test
    fun exifReportIncludesNoExifErrorsAndAllOtherStatuses() = runTest(timeout = 10.seconds) {
        val worker = StandardTestDispatcher(testScheduler)
        ExifStatus.entries.forEach { status ->
            val group = group("IMG.jpg")
            val file = group.files.first()
            val record = ExifRecord(ExifInput.from(file), ExifReadResult(status, error = "bad\n\"input\""), 1000)
            val report = formatDuplicateResults("/archive", DuplicateCandidateFilter(), defaultDuplicateSortRules(),
                listOf(group), worker, exif = mapOf(file.path to record))
            assertTrue(presentExif(record).summary.let(::quoteResultText) in report)
            assertTrue("Error: \"bad\\n\\\"input\\\"\"" in report)
            assertTrue("Reader: \"kim 0.48.0\"" in report)
        }
    }

    private fun group(name: String, folders: List<String> = listOf("b", "a")) =
        DuplicateCandidateGroup(name, 1537, folders.mapIndexed { index, folder ->
            val directory = "/archive/$folder".toPath()
            FileRecord(directory / name, directory, name, "jpg", 1537,
                modifiedAt = if (index == 0) 1000 else null,
                lastScanned = 1, createdAt = if (index == 0) 0 else null)
        })

    @Test
    fun includesSearchContextExactSizesFullPathsAndExplicitTimestampSemantics() = runTest(timeout = 10.seconds) {
        val report = formatDuplicateResults(
            "/archive",
            DuplicateCandidateFilter(DuplicateFileType.IMAGES, "IMG*.jpg"),
            listOf(
                DuplicateSortRule(DuplicateSortCriterion.FILE_SIZE, direction = SortDirection.DESCENDING),
                DuplicateSortRule(DuplicateSortCriterion.NAME, enabled = false),
            ),
            listOf(group("IMG_1.jpg")),
            StandardTestDispatcher(testScheduler),
        )
        assertTrue("not verified duplicates" in report)
        assertTrue("Root: \"/archive\"" in report)
        assertTrue("Type: IMAGES" in report)
        assertTrue("Wildcard (case-insensitive): \"IMG*.jpg\"" in report)
        assertTrue("Sort priority: FILE_SIZE DESCENDING" in report)
        assertFalse("NAME ASCENDING" in report)
        assertTrue("Candidate groups: 1" in report)
        assertTrue("Size per file: 1537 bytes (1.5 KB)" in report)
        assertTrue("Copies: 2" in report)
        assertTrue("Path: \"/archive/b/IMG_1.jpg\"" in report)
        assertTrue("Created: 1970-01-01 00:00:00 UTC" in report)
        assertTrue("Modified: 1970-01-01 00:00:01 UTC" in report)
        assertTrue("Created: unknown" in report)
        assertTrue("Modified: unknown" in report)
    }

    @Test
    fun preservesDisplayedGroupAndMemberOrderWithoutSortingAgain() = runTest(timeout = 10.seconds) {
        val report = formatDuplicateResults("/archive", DuplicateCandidateFilter(), defaultDuplicateSortRules(),
            listOf(group("z.jpg"), group("a.jpg")), StandardTestDispatcher(testScheduler))
        assertTrue(report.indexOf("Group 1: \"z.jpg\"") < report.indexOf("Group 2: \"a.jpg\""))
        assertTrue(report.indexOf("/archive/b/z.jpg") < report.indexOf("/archive/a/z.jpg"))
        assertEquals(4, report.lineSequence().count { it.trimStart().startsWith("Path:") })
    }

    @Test
    fun recommendationSortContextIsIncludedAndExportPreservesDisplayedOrdering() = runTest {
        val report = formatDuplicateResults("/archive", DuplicateCandidateFilter(),
            listOf(DuplicateSortRule(DuplicateSortCriterion.RECOMMENDATION, direction = SortDirection.DESCENDING)),
            listOf(group("z.jpg"), group("a.jpg")), StandardTestDispatcher(testScheduler))
        assertTrue("Sort priority: RECOMMENDATION DESCENDING" in report)
        assertTrue("KEEP_ONE, KEEP_MULTIPLE, REVIEW, PROTECTED" in report)
        assertTrue("unavailable results stay last" in report)
        assertTrue(report.indexOf("Group 1: \"z.jpg\"") < report.indexOf("Group 2: \"a.jpg\""))
    }

    @Test
    fun quotesUnicodeAndEscapesLineBreaksAndQuotesInsteadOfCorruptingReportLines() = runTest(timeout = 10.seconds) {
        val report = formatDuplicateResults("/写真\n\"root\"", DuplicateCandidateFilter(),
            defaultDuplicateSortRules(), listOf(group("雪\n\"copy\".jpg")), StandardTestDispatcher(testScheduler))
        assertTrue("Root: \"/写真\\n\\\"root\\\"\"" in report)
        assertTrue("Group 1: \"雪\\n\\\"copy\\\".jpg\"" in report)
        assertTrue("Path: \"/archive/b/雪\\n\\\"copy\\\".jpg\"" in report)
    }

    @Test
    fun emptyResultsAndDisabledSortHaveUnambiguousDescriptions() = runTest(timeout = 10.seconds) {
        val report = formatDuplicateResults("/archive", DuplicateCandidateFilter(),
            defaultDuplicateSortRules().map { it.copy(enabled = false) }, emptyList(),
            StandardTestDispatcher(testScheduler))
        assertTrue("Candidate groups: 0" in report)
        assertTrue("Sort priority: none (search order)" in report)
        assertTrue("Wildcard (case-insensitive): \"*\"" in report)
        assertFalse("Group 1:" in report)
    }

    @Test
    fun formatsEntireResultSetOffCallerDispatcher() = runTest(timeout = 10.seconds) {
        val worker = TrackingIoDispatcher(StandardTestDispatcher(testScheduler))
        val groups = object : AbstractList<DuplicateCandidateGroup>() {
            override val size = 200
            override fun get(index: Int): DuplicateCandidateGroup {
                worker.assertOnIo("format duplicate results")
                return group("IMG_$index.jpg")
            }
        }
        val report = formatDuplicateResults("/archive", DuplicateCandidateFilter(), defaultDuplicateSortRules(), groups, worker)
        assertTrue("Candidate groups: 200" in report)
        assertTrue("Group 200: \"IMG_199.jpg\"" in report)
        assertEquals(400, report.lineSequence().count { it.trimStart().startsWith("Path:") })
    }

    @Test
    fun cancellationBeforeFormattingDoesNotReadResultsOrPublishText() = runTest(timeout = 10.seconds) {
        var reads = 0
        val groups = object : AbstractList<DuplicateCandidateGroup>() {
            override val size = 1
            override fun get(index: Int): DuplicateCandidateGroup {
                reads++
                return group("IMG.jpg")
            }
        }
        var published: String? = null
        val copying = async(start = CoroutineStart.UNDISPATCHED) {
            published = formatDuplicateResults("/archive", DuplicateCandidateFilter(), defaultDuplicateSortRules(),
                groups, StandardTestDispatcher(testScheduler))
        }
        assertFalse(copying.isCompleted)
        copying.cancel()
        runCurrent()
        copying.join()
        assertEquals(0, reads)
        assertEquals(null, published)
    }

    @Test
    fun reportIncludesAdvisoryScoresFactorBreakdownAndMissingOrAmbiguousLabels() = runTest(timeout = 10.seconds) {
        val group = group("IMG.jpg")
        val file = group.files.first()
        val score = RetentionScorer().score(file, 1000).copy(value = 100.0,
            factors = listOf(ScoreFactor("organization", 100.0, "Preferred chronological folder")))
        val worker = StandardTestDispatcher(testScheduler)
        val report = formatDuplicateResults("/archive", DuplicateCandidateFilter(), defaultDuplicateSortRules(),
            listOf(group), worker, retention = mapOf(file.path to RetentionPresentation(score, RetentionRank.TIED)))
        assertTrue("Retention: 100 - Tied - ambiguous" in report)
        assertTrue("+100: \"Preferred chronological folder\"" in report)
        assertTrue("Retention: Not scored" in report)
        assertTrue("directory-retention v6" in report)
        assertTrue("Assessed: 1970-01-01 00:00:01 UTC" in report)
        assertTrue("not probabilities or deletion instructions" in report)
        val stale = formatDuplicateResults("/archive", DuplicateCandidateFilter(), defaultDuplicateSortRules(),
            listOf(group), worker, retention = mapOf(file.path to RetentionPresentation(score, RetentionRank.STALE)))
        assertTrue("Retention: 100 - Outdated score" in stale)
        val neutral = formatDuplicateResults("/archive", DuplicateCandidateFilter(), defaultDuplicateSortRules(),
            listOf(group), worker,
            retention = mapOf(file.path to RetentionPresentation(score.copy(value = 0.0, factors = emptyList()), RetentionRank.TIED)))
        assertTrue("Retention explanation: \"No positive or negative retention evidence\"" in neutral)
    }

    @Test
    fun copiedResultsPreserveDescendingRetentionMemberOrder() = runTest(timeout = 10.seconds) {
        val group = group("IMG.jpg")
        val scorer = RetentionScorer()
        val scores = group.files.mapIndexed { index, file ->
            file.path to RetentionPresentation(scorer.score(file, 1).copy(value = index * 100.0),
                if (index == 0) RetentionRank.LOWER else RetentionRank.HIGHEST)
        }.toMap()
        val worker = StandardTestDispatcher(testScheduler)
        val displayed = sortDuplicateFilesByRetention(listOf(group), scores, worker)
        val report = formatDuplicateResults("/archive", DuplicateCandidateFilter(), defaultDuplicateSortRules(),
            displayed, worker, retention = scores)
        assertTrue(report.indexOf("/archive/a/IMG.jpg") < report.indexOf("/archive/b/IMG.jpg"))
        assertTrue(report.indexOf("Retention: 100") < report.indexOf("Retention: 0"))
    }
}
