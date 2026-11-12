package com.doronyg.photoarchivededuplicator.ui

import com.doronyg.photoarchivededuplicator.classification.OllamaSettings
import com.doronyg.photoarchivededuplicator.operations.history.ArchiveIdentity
import com.doronyg.photoarchivededuplicator.operations.history.HistoryProblem
import com.doronyg.photoarchivededuplicator.operations.history.OperationHistorySnapshot
import com.doronyg.photoarchivededuplicator.operations.history.OperationHistoryStatistics
import com.doronyg.photoarchivededuplicator.recommendation.FileStatistics
import com.doronyg.photoarchivededuplicator.recommendation.InventoryFileCounts
import com.doronyg.photoarchivededuplicator.recommendation.InventoryStatistics
import com.doronyg.photoarchivededuplicator.recommendation.RecommendationStatisticsReport
import com.doronyg.photoarchivededuplicator.recommendation.StatisticsFileType
import com.doronyg.photoarchivededuplicator.recommendation.aggregateAiRecommendationStatistics
import com.doronyg.photoarchivededuplicator.recommendation.aggregateRecommendationStatistics
import com.doronyg.photoarchivededuplicator.scan.InventorySummary
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CleanupResultsPresentationTest {
    @Test
    fun usesUnionInsteadOfAddingCohortsAndFullInventoryDenominator() = runTest {
        val cohort = aggregateRecommendationStatistics(emptyList()).copy(totals = FileStatistics(4, 400))
        val counts = listOf(2L, 1L, 1L, 0L)
        val report = RecommendationStatisticsReport(cohort, cohort, inventory = InventoryStatistics(
            InventoryFileCounts(6, 4),
            StatisticsFileType.entries.mapIndexed { index, type ->
                type to InventoryFileCounts(counts[index], counts[index])
            }.toMap(),
        ))
        val result = presentCleanupResults(CleanupResultsSnapshot(InventorySummary(emptyMap(), 10, 1234), report), null)
        assertEquals(10L, result.inventoryFiles)
        assertEquals(1234L, result.archiveBytes)
        assertEquals(4L, result.possibleDuplicateFiles)
        assertEquals(0.4f, result.possibleDuplicateFraction)
        assertEquals(StatisticsFileType.entries, result.duplicateTypes.map { it.type })
        assertEquals(counts, result.duplicateTypes.map { it.count })
        assertEquals(4L, result.duplicateTypes.sumOf { it.count })
        assertEquals(listOf(0.5f, 0.25f, 0.25f, 0f), result.duplicateTypes.map { it.fraction })
        assertEquals("40.00%", formatInventoryDuplicatePercentage(
            InventoryFileCounts(result.inventoryFiles, result.possibleDuplicateFiles)))
        assertNull(result.aiRegular)
        assertNull(result.aiSuspected)
        assertNull(result.history)
    }

    @Test
    fun emptyInventoryHasFiniteZeroFractionsAndAllFourTypes() = runTest {
        val result = presentCleanupResults(emptySnapshot(), null)
        assertEquals(0f, result.possibleDuplicateFraction)
        assertEquals(4, result.duplicateTypes.size)
        assertTrue(result.duplicateTypes.all { it.count == 0L && it.fraction == 0f })
    }

    @Test
    fun incompleteOrInconsistentStatisticsAreNotPresentedAsZeroOrValidPercentages() = runTest {
        val empty = emptySnapshot()
        assertFailsWith<NoSuchElementException> {
            presentCleanupResults(empty.copy(analysis = empty.analysis.copy(
                inventory = InventoryStatistics(byFileType = emptyMap()))), null)
        }
        assertFailsWith<IllegalArgumentException> {
            presentCleanupResults(empty.copy(analysis = empty.analysis.copy(
                inventory = InventoryStatistics(all = InventoryFileCounts(2, 2)))), null)
        }
        assertFailsWith<IllegalArgumentException> {
            presentCleanupResults(empty.copy(inventory = empty.inventory.copy(totalBytes = -1)), null)
        }
        assertFailsWith<IllegalArgumentException> { formatCleanupBytes(-1) }
    }

    @Test
    fun distinguishesUnavailableUnreadablePartialAndCompleteEmptyHistory() = runTest {
        val snapshot = emptySnapshot()
        val empty = OperationHistorySnapshot(ArchiveIdentity("archive", "token"), emptyList(), emptyList(),
            OperationHistoryStatistics())
        assertNull(presentCleanupResults(snapshot, null).history)
        assertNull(presentCleanupResults(snapshot, empty.copy(identity = null)).history)
        assertNull(presentCleanupResults(snapshot, empty.copy(readable = false)).history)
        assertEquals(CleanupRecordedMoves(0, 0, 0, false), presentCleanupResults(snapshot, empty).history)
        val partial = empty.copy(
            statistics = OperationHistoryStatistics(completedMoves = 7, logicalBytesMoved = 700,
                filesInBin = 2, logicalBytesInBin = 200, completedRestores = 5, permanentlyDeleted = 999),
            problems = listOf(HistoryProblem("journal", "truncated")),
            complete = false,
        )
        assertEquals(CleanupRecordedMoves(7, 2, 200, true), presentCleanupResults(snapshot, partial).history)
        assertEquals(CleanupRecordedMoves(7, 2, 200, true),
            presentCleanupResults(snapshot, partial.copy(problems = emptyList())).history)
        assertNull(presentCleanupResults(snapshot, partial.copy(readable = false)).history)
    }

    @Test
    fun aiIsUnavailableWithoutConfigurationAndNeverCombinesCohorts() = runTest {
        val base = emptySnapshot()
        val ai = aggregateAiRecommendationStatistics(emptyList(), emptyList(), emptyMap()).copy(
            changedGroups = 2, changedFiles = FileStatistics(3, 300),
            baselineRedundantCandidates = FileStatistics(4, 400),
            currentRedundantCandidates = FileStatistics(5, 500),
            addedRedundantCandidates = FileStatistics(2, 200),
            withdrawnRedundantCandidates = FileStatistics(1, 100),
        )
        val report = base.analysis.copy(regular = base.analysis.regular.copy(ai = ai),
            suspected = base.analysis.suspected.copy(ai = ai.copy(changedGroups = 8)))
        assertNull(presentCleanupResults(base.copy(analysis = report), null).aiRegular)
        assertNull(presentCleanupResults(base.copy(analysis = report), null).aiSuspected)
        val configured = report.copy(classificationContext = OllamaSettings("http://ollama.test", "model").context())
        val result = presentCleanupResults(base.copy(analysis = configured), null)
        assertEquals(CleanupAiImpact(2, 3, 4, 5, 2, 1), result.aiRegular)
        assertEquals(CleanupAiImpact(8, 3, 4, 5, 2, 1), result.aiSuspected)
        assertEquals(CleanupAiImpact(0, 0, 0, 0, 0, 0), presentCleanupResults(base.copy(
            analysis = base.analysis.copy(classificationContext = configured.classificationContext,
                regular = base.analysis.regular.copy(
                    ai = aggregateAiRecommendationStatistics(emptyList(), emptyList(), emptyMap())))), null).aiRegular)
    }

    @Test
    fun countsUseLocaleIndependentGroupingWithoutOverflow() {
        for ((value, text) in listOf(0L to "0", 12L to "12", 999L to "999", 1000L to "1,000",
            1_234_567L to "1,234,567", Long.MAX_VALUE to "9,223,372,036,854,775,807",
            Long.MIN_VALUE to "-9,223,372,036,854,775,808")) {
            assertEquals(text, formatCleanupCount(value))
        }
    }

    @Test
    fun bytesUseAdaptiveDecimalUnitsAndRoundWithoutOverflowOrTruncation() {
        for ((value, text) in listOf(
            0L to "0 B", 999L to "999 B", 1000L to "1 kB", 1250L to "1.3 kB",
            999_949L to "999.9 kB", 999_950L to "1 MB", 1_000_000L to "1 MB",
            850_000_000_000L to "850 GB", 1_250_000_000_000L to "1.3 TB",
            1_000_000_000_000_000L to "1 PB", 1_000_000_000_000_000_000L to "1 EB",
            Long.MAX_VALUE to "9.2 EB",
        )) assertEquals(text, formatCleanupBytes(value))
    }

    private suspend fun emptySnapshot(): CleanupResultsSnapshot {
        val cohort = aggregateRecommendationStatistics(emptyList())
        return CleanupResultsSnapshot(InventorySummary(emptyMap(), 0, 0),
            RecommendationStatisticsReport(cohort, cohort))
    }
}
