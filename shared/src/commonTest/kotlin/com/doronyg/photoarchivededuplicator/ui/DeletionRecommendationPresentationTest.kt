package com.doronyg.photoarchivededuplicator.ui

import com.doronyg.photoarchivededuplicator.UiChoices
import com.doronyg.photoarchivededuplicator.UiTab
import com.doronyg.photoarchivededuplicator.decodeUiChoices
import com.doronyg.photoarchivededuplicator.encodeUiChoices
import com.doronyg.photoarchivededuplicator.exif.ExifInput
import com.doronyg.photoarchivededuplicator.exif.ExifMetadata
import com.doronyg.photoarchivededuplicator.exif.ExifReadResult
import com.doronyg.photoarchivededuplicator.exif.ExifRecord
import com.doronyg.photoarchivededuplicator.exif.ExifStatus
import com.doronyg.photoarchivededuplicator.recommendation.DeletionAnalysisSnapshot
import com.doronyg.photoarchivededuplicator.recommendation.DeletionCategoryResults
import com.doronyg.photoarchivededuplicator.recommendation.DeletionRecommendationCategory
import com.doronyg.photoarchivededuplicator.recommendation.DeletionRecommendationReport
import com.doronyg.photoarchivededuplicator.recommendation.DeletionRecommendationSummary
import com.doronyg.photoarchivededuplicator.recommendation.DuplicateRecommendationEngine
import com.doronyg.photoarchivededuplicator.recommendation.FileStatistics
import com.doronyg.photoarchivededuplicator.recommendation.SameDirectoryMetadataProvider
import com.doronyg.photoarchivededuplicator.retention.RetentionPresentation
import com.doronyg.photoarchivededuplicator.retention.RetentionRank
import com.doronyg.photoarchivededuplicator.retention.RetentionScorer
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeletionRecommendationPresentationTest {
    private val first = DeletionCategoryResults(
        DeletionRecommendationCategory("first", "First", "First explanation"), emptyList(),
        DeletionRecommendationSummary(2, FileStatistics(3, 1_048_576)))
    private val empty = DeletionCategoryResults(
        DeletionRecommendationCategory("empty", "Empty", "Empty explanation"), emptyList(),
        DeletionRecommendationSummary())
    private val report = DeletionRecommendationReport(listOf(first, empty), first.summary)

    @Test
    fun categoryGridAdaptsToAvailableWidthIncludingSpacingAndNarrowScreens() {
        for ((width, columns) in listOf(0 to 1, 200 to 1, 320 to 1, 651 to 1,
            652 to 2, 983 to 2, 984 to 3, 1400 to 4, 2400 to 6)) {
            val actual = deletionCategoryGridColumns(width, 320, 12, 6)
            assertEquals(columns, actual, "Available width: $width")
            val cardWidth = (width - 12 * (actual - 1)) / actual
            assertTrue(cardWidth >= 0)
            assertTrue(actual * cardWidth + 12 * (actual - 1) <= width)
        }
    }

    @Test
    fun categoryGridDoesNotCreateEmptyColumnsOrRequireCards() {
        assertEquals(1, deletionCategoryGridColumns(2400, 320, 12, 1))
        assertEquals(2, deletionCategoryGridColumns(2400, 320, 12, 2))
        assertEquals(0, deletionCategoryGridColumns(2400, 320, 12, 0))
    }

    @Test
    fun categorySelectionUsesStableIdentifiersAndSupportsEmptyFutureCategories() {
        assertEquals(first, selectedDeletionCategory(report, null))
        assertEquals(empty, selectedDeletionCategory(report, "empty"))
        assertEquals(first, selectedDeletionCategory(report, "removed-category"))
        assertNull(selectedDeletionCategory(report.copy(categories = emptyList()), "first"))
    }

    @Test
    fun summariesAreExplicitlyProposalsNotClaimedReclaimableBytes() {
        assertEquals("2 groups | 3 files | 1.05 MB", deletionSummaryText(first.summary))
        assertEquals("0 groups | 0 files | 0.00 MB", deletionSummaryText(empty.summary))
        assertEquals("Review before deleting: matching metadata does not prove identical file contents.", DELETION_REVIEW_NOTICE)
    }

    @Test
    fun recommendationsTabFollowsSuspectedDuplicatesAndPersistsByName() {
        assertEquals("Deletion Recommendations", UiTab.DELETION_RECOMMENDATIONS.title)
        assertEquals(UiTab.DELETION_RECOMMENDATIONS, UiTab.entries[UiTab.SUSPECTED_DUPLICATES.ordinal + 1])
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
        assertTrue(quoteResultText(candidate.advice.explanation) in fileDecisionConfidenceDetailsText(candidate.advice))
        assertTrue(files.first().path.toString() in fileDecisionConfidenceDetailsText(candidate.advice))
        assertTrue("2023" in presentExif(candidate.exif).summary)
        assertTrue("Canon" in presentExif(candidate.exif).summary)
        assertTrue("Duplicate confidence" in groupConfidenceSummaryText(group.recommendation))
        assertTrue("matching capture metadata" in group.evidenceSummary)
    }
}
