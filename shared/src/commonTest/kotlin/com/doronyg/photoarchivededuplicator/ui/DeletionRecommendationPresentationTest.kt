package com.doronyg.photoarchivededuplicator.ui

import com.doronyg.photoarchivededuplicator.*
import com.doronyg.photoarchivededuplicator.recommendation.*
import com.doronyg.photoarchivededuplicator.exif.*
import com.doronyg.photoarchivededuplicator.retention.*
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import okio.Path.Companion.toPath
import kotlin.test.*

class DeletionRecommendationPresentationTest {
    private val first = DeletionCategoryResults(
        DeletionRecommendationCategory("first", "First", "First explanation"), emptyList(),
        DeletionRecommendationSummary(2, FileStatistics(3, 1_048_576)))
    private val empty = DeletionCategoryResults(
        DeletionRecommendationCategory("empty", "Empty", "Empty explanation"), emptyList(),
        DeletionRecommendationSummary())
    private val report = DeletionRecommendationReport(listOf(first, empty), first.summary)

    @Test
    fun categorySelectionUsesStableIdentifiersAndSupportsEmptyFutureCategories() {
        assertEquals(first, selectedDeletionCategory(report, null))
        assertEquals(empty, selectedDeletionCategory(report, "empty"))
        assertEquals(first, selectedDeletionCategory(report, "removed-category"))
        assertNull(selectedDeletionCategory(report.copy(categories = emptyList()), "first"))
    }

    @Test
    fun summariesAreExplicitlyProposalsNotClaimedReclaimableBytes() {
        assertEquals("2 groups | 3 proposed files | 1.05 MB", deletionSummaryText(first.summary))
        assertEquals("0 groups | 0 proposed files | 0.00 MB", deletionSummaryText(empty.summary))
        assertTrue("not verified reclaimable space" in DELETION_REVIEW_NOTICE)
        assertTrue("No files are changed" in DELETION_REVIEW_NOTICE)
    }

    @Test
    fun recommendationsTabPersistsByNameWithoutChangingOtherTabOrdering() {
        assertEquals("Deletion Recommendations", UiTab.DELETION_RECOMMENDATIONS.title)
        assertEquals(UiTab.STATISTICS, UiTab.entries[UiTab.SUSPECTED_DUPLICATES.ordinal + 1])
        val choices = UiChoices(selectedTab = UiTab.DELETION_RECOMMENDATIONS)
        assertEquals("DELETION_RECOMMENDATIONS", encodeUiChoices(choices)["selectedTab"])
        assertEquals(choices, decodeUiChoices(encodeUiChoices(choices)))
    }

    @Test
    fun evidencePresentationUsesActualEngineReasonsReplacementAndCachedMetadata() {
        val files = listOf("IMG.jpg", "IMG-001.jpg").map {
            FileRecord("/Photos/Family/$it".toPath(), "/Photos/Family".toPath(), it, "jpg", 120_000, 1, 2)
        }
        val exif = files.associate { it.path to ExifRecord(ExifInput.from(it),
            ExifReadResult(ExifStatus.EXTRACTED, ExifMetadata(capturedAt = "2023:02:18 12:44:25",
                make = "Canon", model = "Camera", width = 4000, height = 3000)), 3) }
        val scores = files.associate {
            it.path to RetentionPresentation(RetentionScorer().score(it, 4, exif[it.path]), RetentionRank.CLOSE)
        }
        val advice = DuplicateRecommendationEngine().recommend(files, exif, scores)
        val group = assertNotNull(SameDirectoryMetadataProvider().recommend(
            DeletionAnalysisSnapshot(files, advice, exif, scores, emptyMap())))
        assertEquals(DeletionRecommendationSummary(1, FileStatistics(1, 120_000)), deletionGroupSummary(group))
        val candidate = group.candidates.single()
        assertTrue(quoteResultText(candidate.advice.explanation) in fileRecommendationText(candidate.advice))
        assertTrue(files.first().path.toString() in fileDecisionConfidenceDetailsText(candidate.advice))
        assertTrue("2023" in presentExif(candidate.exif).summary)
        assertTrue("Canon" in presentExif(candidate.exif).summary)
        assertTrue("not content-verified" in groupConfidenceDetailsText(group.recommendation))
        assertTrue("not byte-verified" in group.evidenceSummary)
    }
}
