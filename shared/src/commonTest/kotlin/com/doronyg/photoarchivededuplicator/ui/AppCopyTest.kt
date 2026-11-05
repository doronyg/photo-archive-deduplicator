package com.doronyg.photoarchivededuplicator.ui

import com.doronyg.photoarchivededuplicator.recommendation.FileRecommendationRole
import com.doronyg.photoarchivededuplicator.strings.AppStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppCopyTest {
    @Test
    fun fileRowsAreConciseAndKeepTheActualExplanationInDetails() {
        val recommendation = confidencePresentationFixture()
        val candidate = recommendation.files.values.single { it.role == FileRecommendationRole.REDUNDANT_CANDIDATE }
        assertEquals("Likely redundant", fileRecommendationText(candidate))
        val details = fileDecisionConfidenceDetailsText(candidate)
        assertTrue(quoteResultText(candidate.explanation) in details)
        assertTrue(quoteResultText(candidate.retentionDecisionConfidence.explanation) in details)
        assertTrue(quoteResultText(candidate.retainedReplacementPath.toString()) in details)
        assertFalse("Heuristic scores" in details)
        assertFalse("REVIEW ONLY" in details)
    }

    @Test
    fun onlyReviewHeaderContainsTheSafetyNoticeNotEveryGroup() {
        assertEquals(AppStrings.review.notice, DELETION_REVIEW_NOTICE)
        assertTrue("does not prove identical file contents" in DELETION_REVIEW_NOTICE)
        val evidence = listOf(AppStrings.review.sameDirectoryEvidence,
            AppStrings.review.folderMismatchEvidence("2018-08-26"))
        evidence.forEach {
            assertFalse("not byte-verified" in it)
            assertFalse("No files are changed" in it)
            assertFalse("REVIEW ONLY" in it)
        }
        assertEquals("Retain", AppStrings.review.retainedCopy)
        assertEquals("Redundant copy", AppStrings.review.redundantCopy)
    }

    @Test
    fun sharedCopyKeepsDatesAndLabelsTogether() {
        assertEquals("Shared capture date: 2018-08-26. The retained copy matches its folder date; redundant copies do not. " +
            "Only folder-date mismatches are shown.", AppStrings.review.folderMismatchEvidence("2018-08-26"))
        assertEquals("4 groups | 7 files | 2.00 MB", AppStrings.review.summary(4, 7, "2.00 MB"))
        assertEquals("EXIF and folder date mismatch", AppStrings.review.folderMismatchName)
    }
}
