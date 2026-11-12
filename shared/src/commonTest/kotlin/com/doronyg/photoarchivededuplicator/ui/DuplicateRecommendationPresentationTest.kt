package com.doronyg.photoarchivededuplicator.ui

import com.doronyg.photoarchivededuplicator.exif.ExifInput
import com.doronyg.photoarchivededuplicator.exif.ExifMetadata
import com.doronyg.photoarchivededuplicator.exif.ExifReadResult
import com.doronyg.photoarchivededuplicator.exif.ExifRecord
import com.doronyg.photoarchivededuplicator.exif.ExifStatus
import com.doronyg.photoarchivededuplicator.recommendation.ConfidenceAssessment
import com.doronyg.photoarchivededuplicator.recommendation.ConfidenceEvidence
import com.doronyg.photoarchivededuplicator.recommendation.ConfidenceReason
import com.doronyg.photoarchivededuplicator.recommendation.DuplicateConfidenceAssessment
import com.doronyg.photoarchivededuplicator.recommendation.DuplicateGroupRecommendation
import com.doronyg.photoarchivededuplicator.recommendation.DuplicateRecommendationEngine
import com.doronyg.photoarchivededuplicator.recommendation.FileDuplicateConfidence
import com.doronyg.photoarchivededuplicator.recommendation.FileRecommendationRole
import com.doronyg.photoarchivededuplicator.recommendation.RecommendationAction
import com.doronyg.photoarchivededuplicator.recommendation.RecommendationReason
import com.doronyg.photoarchivededuplicator.retention.RetentionPresentation
import com.doronyg.photoarchivededuplicator.retention.RetentionRank
import com.doronyg.photoarchivededuplicator.retention.RetentionScorer
import com.doronyg.photoarchivededuplicator.scan.DuplicateCandidateFilter
import com.doronyg.photoarchivededuplicator.scan.DuplicateCandidateGroup
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DuplicateRecommendationPresentationTest {
    private val directory = "/Pictures/Zoo".toPath()
    private val original = FileRecord(directory / "IMG_1234.jpg", directory, "IMG_1234.jpg", "jpg", 120_000, null, 1)
    private val copy = original.copy(path = directory / "IMG_1234-001.jpg", name = "IMG_1234-001.jpg")
    private val files = listOf(copy, original)
    private val exif = files.associate { it.path to ExifRecord(ExifInput.from(it),
        ExifReadResult(ExifStatus.EXTRACTED, ExifMetadata(capturedAt = "2023:02:18 12:44:25")), 2) }
    private val retention = files.associate { it.path to RetentionPresentation(RetentionScorer().score(it, 3, exif[it.path]), RetentionRank.CLOSE) }
    private val recommendation = DuplicateRecommendationEngine().recommend(files, exif, retention)

    @Test
    fun summaryAndPerFileLabelsDistinguishRecommendationsFromConfirmedDuplicates() {
        assertEquals("Review", groupRecommendationText(recommendation))
        assertTrue(fileRecommendationText(recommendation.files.getValue(original.path)).contains("Preserve for review"))
        assertTrue(fileRecommendationText(recommendation.files.getValue(copy.path)).contains("Preserve for review"))
        assertEquals(60, recommendation.duplicateConfidence.score)
        recommendation.files.values.forEach {
            assertEquals(0, it.retentionDecisionConfidence.score)
            assertEquals(null, it.retainedReplacementPath)
            assertEquals("Retention confidence: 0/100 (Very low)", fileDecisionConfidenceSummaryText(it))
        }
        assertTrue(recommendation.redundantCandidates.isEmpty())
        assertTrue("Duplicate confidence: 60/100 (Medium)" in groupConfidenceSummaryText(recommendation))
        assertFalse("Retention" in groupConfidenceSummaryText(recommendation))
        assertEquals("Loading recommendation...", groupRecommendationText(null))
        assertEquals("Loading recommendation...", fileRecommendationText(null))
    }

    @Test
    fun summaryShowsOnlyGroupDuplicateConfidenceAndFilesHaveIndependentDecisionConfidence() {
        val recommendation = confidencePresentationFixture()
        assertEquals(
            "Duplicate confidence: 100/100 (Very high)",
            groupConfidenceSummaryText(recommendation),
        )
        assertFalse("100" in groupRecommendationText(recommendation))
        assertFalse("%" in groupConfidenceSummaryText(recommendation))
        val details = groupConfidenceDetailsText(recommendation)
        assertFalse("Heuristic scores, not probabilities" in details)
        assertFalse("100%" in details)
        assertFalse("Retention" in details)
        val files = recommendation.files.values.toList()
        assertEquals("Retention confidence: 95/100 (Very high)", fileDecisionConfidenceSummaryText(files[0]))
        assertEquals("Retention confidence: 75/100 (High)", fileDecisionConfidenceSummaryText(files[1]))
        assertFileDecisionPresentationDetails(
            files.joinToString("\n") { fileDecisionConfidenceSummaryText(it) + "\n" + fileDecisionConfidenceDetailsText(it) },
        )
    }

    @Test
    fun expandedConfidenceDetailsQuoteEvidenceAndPreserveComparisonOrder() {
        assertConfidencePresentationDetails(groupConfidenceDetailsText(confidencePresentationFixture()))
    }

    @Test
    fun unavailableReferenceAndEmptyComparisonsAreExplicit() {
        val recommendation = confidencePresentationFixture()
        val details = groupConfidenceDetailsText(recommendation.copy(
            duplicateConfidence = recommendation.duplicateConfidence.copy(referenceFile = null, comparisons = emptyMap()),
        ))
        assertTrue("Reference: Unavailable" in details)
        assertTrue("No comparisons available." in details)
        assertFalse("Retention-confidence explanation:" in details)
    }

    @Test
    fun copiedResultsIncludeGroupAndPerFileReasonsWithoutChangingDisplayedOrder() = runTest {
        val group = DuplicateCandidateGroup(original.name, original.sizeBytes, files)
        val text = formatDuplicateResults("/Pictures", DuplicateCandidateFilter(), emptyList(), listOf(group),
            StandardTestDispatcher(testScheduler), retention, exif,
            recommendations = files.associate { it.path to recommendation })
        assertTrue("Group recommendation: REVIEW" in text)
        assertTrue("DUPLICATE_CONFIDENCE_TOO_LOW" in text)
        assertTrue("File recommendation: REVIEW" in text)
        assertFalse("File recommendation: REDUNDANT_CANDIDATE" in text)
        assertEquals(2, text.lineSequence().count { "Retention confidence: 0/100 (Very low)" in it })
        assertFalse("Retained replacement: \"" in text)
        assertTrue("Normalized filename: \"IMG_1234.jpg\"" in text)
        assertTrue(text.indexOf(quoteResultText(copy.path.toString())) < text.indexOf("  Path: ${quoteResultText(original.path.toString())}"))
        assertTrue(RecommendationReason.NUMBERED_COPY_SUFFIX.name in text)
        assertFalse("not content-verified" in text)
        assertFalse("No files have been changed" in text)
    }

}

internal fun confidencePresentationFixture(): DuplicateGroupRecommendation {
    val directory = "/Pictures/\u96ea\n\"album\"".toPath()
    val reference = FileRecord(directory / "reference.jpg", directory, "reference.jpg", "jpg", 120_000, 1, 1)
    val files = listOf(reference, reference.copy(path = directory / "z.jpg", name = "z.jpg"),
        reference.copy(path = directory / "a.jpg", name = "a.jpg"))
    val duplicate = ConfidenceAssessment(100, setOf(ConfidenceReason.WEAKEST_MEMBER), "Duplicate\n\"evidence\"\t\u0001")
    val comparisons = files.drop(1).associate { file ->
        file.path to FileDuplicateConfidence(file, reference,
            ConfidenceAssessment(100, setOf(ConfidenceReason.NORMALIZED_FILENAME_MATCH), "Compared\n\"file\""),
            listOf(
                ConfidenceEvidence(ConfidenceReason.NORMALIZED_FILENAME_MATCH, 30, "Name\n\"match\""),
                ConfidenceEvidence(ConfidenceReason.SIZE_NOT_SCORED, 0, "Size is a prerequisite\t only"),
            ), hasContradictions = false)
    }
    val base = DuplicateRecommendationEngine().recommend(files, emptyMap(), emptyMap())
    return base.copy(
        action = RecommendationAction.KEEP_MULTIPLE,
        duplicateConfidence = DuplicateConfidenceAssessment(reference, duplicate, comparisons),
        files = files.associate { file ->
            val candidate = file.path == files[1].path
            file.path to base.files.getValue(file.path).copy(
                role = if (candidate) FileRecommendationRole.REDUNDANT_CANDIDATE else FileRecommendationRole.PRESERVE,
                retentionDecisionConfidence = ConfidenceAssessment(
                    if (candidate) 75 else if (file.path == reference.path) 95 else 90,
                    setOf(ConfidenceReason.SAME_DIRECTORY),
                    if (candidate) "Candidate\r\"retained survivor\"\t\u0001" else "Keep\n\"collection representative\"",
                ),
                retainedReplacementPath = if (candidate) reference.path else null,
            )
        },
    )
}

internal fun assertConfidencePresentationDetails(text: String, includesSummary: Boolean = false) {
    val recommendation = confidencePresentationFixture()
    assertTrue("Reference: ${quoteResultText(recommendation.duplicateConfidence.referenceFile!!.path.toString())}" in text)
    assertTrue("\"Duplicate\\n\\\"evidence\\\"\\t\\u0001\"" in text)
    assertFalse(recommendation.duplicateConfidence.assessment.explanation in text)
    assertFalse("Duplicate-confidence reasons:" in text)
    val comparisons = recommendation.duplicateConfidence.comparisons.values.toList()
    comparisons.forEach { comparison ->
        assertTrue("Comparison: ${quoteResultText(comparison.file.path.toString())}" in text)
        assertFalse(comparison.file.path.toString() in text)
        assertFalse(comparison.assessment.explanation in text)
        comparison.evidence.forEach { assertFalse(it.explanation in text) }
    }

    assertTrue(text.indexOf("Comparison: ${quoteResultText(comparisons[0].file.path.toString())}") <
        text.indexOf("Comparison: ${quoteResultText(comparisons[1].file.path.toString())}"))
    assertEquals(if (includesSummary) 3 else 2,
        text.lineSequence().count { "Duplicate confidence: 100/100 (Very high)" in it })
    assertEquals(2, text.lineSequence().count { "\"Compared\\n\\\"file\\\"\"" in it })
    assertFalse("Comparison reasons:" in text)
    assertEquals(2, text.lineSequence().count { "+30 points: \"Name\\n\\\"match\\\"\"" in it })
    assertEquals(2, text.lineSequence().count { "0 points: \"Size is a prerequisite\\t only\"" in it })
    assertFalse("Heuristic scores, not probabilities" in text)
}

internal fun assertFileDecisionPresentationDetails(text: String) {
    val files = confidencePresentationFixture().files.values.toList()
    files.forEach { advice ->
        assertTrue(fileDecisionConfidenceSummaryText(advice) in text)
        assertTrue(quoteResultText(advice.retentionDecisionConfidence.explanation) in text)
        assertTrue(quoteResultText(advice.explanation) in text)
        assertFalse(advice.retentionDecisionConfidence.explanation in text)
        assertFalse("Retention-confidence reasons:" in text)
    }
    val candidate = files.single { it.role == FileRecommendationRole.REDUNDANT_CANDIDATE }
    assertTrue("Retained replacement: ${quoteResultText(candidate.retainedReplacementPath.toString())}" in text)
    assertFalse(candidate.retainedReplacementPath.toString() in text)
    assertFalse("Heuristic scores, not probabilities" in text)
}
