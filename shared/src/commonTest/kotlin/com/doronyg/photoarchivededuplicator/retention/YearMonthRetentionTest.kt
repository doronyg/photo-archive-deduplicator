package com.doronyg.photoarchivededuplicator.retention

import com.doronyg.photoarchivededuplicator.scan.DuplicateCandidateGroup
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class YearMonthRetentionTest {
    private val scorer = RetentionScorer()
    private fun file(folder: String, name: String = "20240820_155629.jpg"): FileRecord {
        val directory = "/Photos/$folder".toPath()
        return FileRecord(directory / name, directory, name, "jpg", 5_700_000,
            Instant.parse("2024-08-20T13:56:37Z").toEpochMilliseconds(), 1,
            Instant.parse("2024-08-24T10:42:27Z").toEpochMilliseconds())
    }

    @Test
    fun reportedYearMonthCopyGetsThirtyWithoutNeedingTimestampOrFilenameEvidence() {
        val score = scorer.score(file("PhotoLibrary/2024/08"), 1)
        assertEquals(30.0, score.value)
        assertEquals(30.0, score.factors.single { it.key == "directory-date" }.contribution)
        assertTrue(score.factors.single().explanation.contains("year/month"))
    }

    @Test
    fun yearOnlyStaysWeakerAndFullDatesRetainExistingBonuses() {
        assertEquals(10.0, scorer.score(file("2024"), 1).value)
        assertEquals(30.0, scorer.score(file("2024/08/Album"), 1).value)
        assertEquals(30.0, scorer.score(file("2024/08/2015"), 1).value)
        assertEquals(40.0, scorer.score(file("2024/08/20240819"), 1).value)
        assertEquals(100.0, scorer.score(file("2024/08/2024-08-19"), 1).value)
    }

    @Test
    fun invalidMonthDoesNotEarnYearMonthBonusAndLocationPenaltiesStillApply() {
        for (folder in listOf("2024/00", "2024/13", "2024/not-a-month", "2024/08suffix")) {
            assertEquals(10.0, scorer.score(file(folder), 1).value, folder)
        }
        assertEquals(5.0, scorer.score(file("MobileBackup/2024/08"), 1).value)
        assertEquals(-30.0, scorer.score(file("Temp/2024/08"), 1).value)
    }

    @Test
    fun consistentPartialDateOutranksKnownContradictoryFullDateWithinSameGroup() {
        val partial = file("2015/07", "IMG-20150723-WA0003.jpg")
        val wrongFull = file("2016-06-08", partial.name)
        val files = listOf(wrongFull, partial)
        val scores = files.associate { it.path to scorer.score(it, 1) }
        assertEquals(30.0, scores.getValue(partial.path).value)
        assertEquals(20.0, scores.getValue(wrongFull.path).value)
        val presented = presentRetentionGroup(DuplicateCandidateGroup(partial.name, partial.sizeBytes, files),
            scores, scorer.identity, scorer.ambiguityMargin)
        assertEquals(RetentionRank.HIGHEST, presented.getValue(partial.path).rank)
        assertEquals(RetentionRank.LOWER, presented.getValue(wrongFull.path).rank)
    }

    @Test
    fun yearMonthWeightIsIndependentlyConfigurableAndChangesCacheIdentity() {
        val configured = RetentionScorer(RetentionConfig(yearMonthDateBonus = 35.0))
        assertEquals(35.0, configured.score(file("PhotoLibrary/2024/08"), 1).value)
        assertEquals(10.0, configured.score(file("2024"), 1).value)
        assertTrue(configured.identity != scorer.identity)
    }
}
