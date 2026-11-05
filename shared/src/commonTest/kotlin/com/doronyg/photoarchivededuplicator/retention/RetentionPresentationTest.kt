package com.doronyg.photoarchivededuplicator.retention

import com.doronyg.photoarchivededuplicator.scan.DuplicateCandidateGroup
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RetentionPresentationTest {
    private val scorer = RetentionScorer()
    private fun file(folder: String, createdAt: Long? = null): FileRecord {
        val directory = "/Pictures/$folder".toPath()
        return FileRecord(directory / "photo.jpg", directory, "photo.jpg", "jpg", 12, null, 1, createdAt)
    }

    private fun present(files: List<FileRecord>, scores: List<FileScore>) =
        presentRetentionGroup(DuplicateCandidateGroup("photo.jpg", 12, files),
            scores.associateBy { it.input.path }, scorer.identity, scorer.ambiguityMargin)

    @Test
    fun clearLeaderAndLowerCopyHaveConciseExplainableLabels() {
        val files = listOf(file("2007-06-06"), file("Google Photos"))
        val result = present(files, files.map { scorer.score(it, 1) })
        assertEquals(RetentionRank.HIGHEST, result.getValue(files[0].path).rank)
        assertEquals(RetentionRank.LOWER, result.getValue(files[1].path).rank)
        assertEquals("100", result.getValue(files[0].path).scoreText)
        assertEquals(
            "Preferred chronological folder yyyy-MM-dd or date-prefixed folder (2007-06-06).",
            result.getValue(files[0].path).mainReason,
        )
        assertEquals("Highest retention score", result.getValue(files[0].path).label)
        assertEquals("Lower retention score", result.getValue(files[1].path).label)
        assertTrue(result.getValue(files[0].path).score!!.factors.isNotEmpty())
    }

    @Test
    fun equalChronologicalCopiesAreTiedRatherThanChosenByFolderAgeOrInputOrder() {
        val files = listOf(file("2024-08-16"), file("2007-06-06"))
        val scores = files.map { scorer.score(it, 1) }
        val result = present(files, scores)
        assertEquals(setOf(RetentionRank.TIED), result.values.map { it.rank }.toSet())
        assertEquals(result, present(files.reversed(), scores.reversed()))
    }

    @Test
    fun weakTimestampLeadIsStillAmbiguousAndTenPointMarginIsInclusive() {
        val files = listOf(file("1970-01-01", createdAt = 0), file("2024-08-16"))
        val scores = files.map { scorer.score(it, 1) }
        assertEquals(setOf(RetentionRank.CLOSE), present(files, scores).values.map { it.rank }.toSet())
        val boundary = listOf(scores[0].copy(value = 110.0), scores[1].copy(value = 100.0))
        assertEquals(setOf(RetentionRank.CLOSE), present(files, boundary).values.map { it.rank }.toSet())
        val outside = listOf(scores[0].copy(value = 111.0), scores[1].copy(value = 100.0))
        assertEquals(RetentionRank.HIGHEST, present(files, outside).getValue(files[0].path).rank)
    }

    @Test
    fun missingScoresPreventACompleteGroupsLeaderRecommendation() {
        val files = listOf(file("2007-06-06"), file("Google Photos"))
        val result = present(files, listOf(scorer.score(files[0], 1)))
        assertEquals(RetentionRank.INCOMPLETE, result.getValue(files[0].path).rank)
        assertEquals(RetentionRank.MISSING, result.getValue(files[1].path).rank)
        assertEquals("Not scored", result.getValue(files[1].path).label)
        assertEquals("Incomplete group - no recommendation", result.getValue(files[0].path).label)
        assertEquals("-", result.getValue(files[1].path).scoreText)
        assertEquals("No positive or negative retention evidence", result.getValue(files[1].path).mainReason)
    }

    @Test
    fun changedMetadataOrWrongAlgorithmVersionIsShownAsStale() {
        val files = listOf(file("2007-06-06"), file("Google Photos"))
        val scores = files.map { scorer.score(it, 1) }
        val changed = files[0].copy(sizeBytes = 99)
        assertEquals(RetentionRank.STALE,
            present(listOf(changed, files[1]), scores).getValue(changed.path).rank)
        val oldVersion = scores[0].copy(identity = scorer.identity.copy(algorithmVersion = "old"))
        val result = present(files, listOf(oldVersion, scores[1]))
        assertEquals(RetentionRank.STALE, result.getValue(files[0].path).rank)
        assertEquals(RetentionRank.INCOMPLETE, result.getValue(files[1].path).rank)
    }
}
