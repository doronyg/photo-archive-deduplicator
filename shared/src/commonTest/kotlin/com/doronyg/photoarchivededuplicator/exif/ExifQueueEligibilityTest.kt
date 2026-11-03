package com.doronyg.photoarchivededuplicator.exif

import com.doronyg.photoarchivededuplicator.retention.FileScoreInput
import com.doronyg.photoarchivededuplicator.retention.RetentionPresentation
import com.doronyg.photoarchivededuplicator.retention.RetentionRank
import com.doronyg.photoarchivededuplicator.retention.RetentionScorer
import com.doronyg.photoarchivededuplicator.scan.DuplicateCandidateGroup
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExifQueueEligibilityTest {
    private val scorer = RetentionScorer()

    private fun group(vararg folders: String) = DuplicateCandidateGroup("photo.jpg", 12, folders.map { folder ->
        val directory = "/archive/$folder".toPath()
        FileRecord(directory / "photo.jpg", directory, "photo.jpg", "jpg", 12, null, 1)
    })

    private fun scores(group: DuplicateCandidateGroup, vararg values: Double) =
        group.files.mapIndexed { index, file ->
            file.path to RetentionPresentation(scorer.score(file, 1).copy(value = values[index]), RetentionRank.CLOSE)
        }.toMap()

    @Test
    fun topTwoScoresUseInclusiveTenPointGapAndIgnoreLowOutliers() {
        val group = group("2007-06-06", "2008-07-07", "Temp")
        assertTrue(isExifQueueEligible(group, scores(group, -500.0, 100.0, 90.0)))
        assertFalse(isExifQueueEligible(group, scores(group, 100.0, 89.0, -500.0)))
        assertTrue(isExifQueueEligible(group, scores(group, 100.0, 100.0, -500.0)))
    }

    @Test
    fun anyMemberCanSupplyACompleteDateIncludingAlternativeAndLabelledFolders() {
        for (folder in listOf("2007-06-06 Summer", "2007/06/06", "2007_06_06", "20070606")) {
            val group = group("plain-a", "plain-b", folder)
            assertTrue(isExifQueueEligible(group, scores(group, 100.0, 99.0, -500.0)), folder)
        }
    }

    @Test
    fun yearAndYearMonthOrInvalidDatesCannotEnableExtraction() {
        for (folder in listOf("2007", "2007/06", "2007-06", "2007-02-30", "plain")) {
            val group = group(folder, "other")
            assertFalse(isExifQueueEligible(group, scores(group, 100.0, 100.0)), folder)
        }
    }

    @Test
    fun missingIncompleteStaleAndMismatchedInputsAreRejected() {
        val group = group("2007-06-06", "2008-07-07", "Temp")
        val current = scores(group, 100.0, 100.0, -500.0)
        val file = group.files.last()
        assertFalse(isExifQueueEligible(group, current - file.path))
        for (rank in listOf(RetentionRank.MISSING, RetentionRank.STALE, RetentionRank.INCOMPLETE)) {
            assertFalse(isExifQueueEligible(group, current + (file.path to current.getValue(file.path).copy(rank = rank))))
        }
        val score = current.getValue(file.path).score!!
        assertFalse(isExifQueueEligible(group, current + (file.path to RetentionPresentation(
            score.copy(input = FileScoreInput.from(file.copy(sizeBytes = 99))), RetentionRank.LOWER,
        ))))
        assertFalse(isExifQueueEligible(group, current + (file.path to RetentionPresentation(
            score.copy(identity = score.identity.copy(algorithmVersion = "old")), RetentionRank.LOWER,
        ))))
        assertFalse(isExifQueueEligible(group, current.mapValues { (_, presentation) ->
            presentation.copy(score = presentation.score!!.copy(identity = score.identity.copy(algorithmVersion = "old")))
        }))
    }
}
