package com.doronyg.photoarchivededuplicator.ui

import com.doronyg.photoarchivededuplicator.UiChoices
import com.doronyg.photoarchivededuplicator.UiTab
import com.doronyg.photoarchivededuplicator.decodeUiChoices
import com.doronyg.photoarchivededuplicator.encodeUiChoices
import com.doronyg.photoarchivededuplicator.recommendation.RecommendationStatisticsReport
import com.doronyg.photoarchivededuplicator.recommendation.aggregateRecommendationStatistics
import com.doronyg.photoarchivededuplicator.recommendation.aggregateAiRecommendationStatistics
import com.doronyg.photoarchivededuplicator.classification.OllamaSettings
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RecommendationStatisticsPresentationTest {
    @Test
    fun statisticsTabFollowsSuspectedDuplicatesAndPersistsByName() {
        assertEquals(UiTab.STATISTICS, UiTab.entries[UiTab.SUSPECTED_DUPLICATES.ordinal + 1])
        val choices = UiChoices(selectedTab = UiTab.STATISTICS)
        assertEquals("STATISTICS", encodeUiChoices(choices)["selectedTab"])
        assertEquals(choices, decodeUiChoices(encodeUiChoices(choices)))
    }

    @Test
    fun megabytesUseDecimalUnitsAndTwoRoundedPlacesWithoutLongOverflow() {
        assertEquals("0.00 MB", formatStatisticsMegabytes(0))
        assertEquals("1.00 MB", formatStatisticsMegabytes(1_000_000))
        assertEquals("1.05 MB", formatStatisticsMegabytes(1_048_576))
        assertEquals("1.00 MB", formatStatisticsMegabytes(999_999))
        assertEquals("9223372036854.78 MB", formatStatisticsMegabytes(Long.MAX_VALUE))
        assertFailsWith<IllegalArgumentException> { formatStatisticsMegabytes(-1) }
    }

    @Test
    fun plainTextIncludesBothCohortsBothKindsOfRecommendationsAndFileTypesEvenWhenEmpty() = runTest {
        val empty = aggregateRecommendationStatistics(emptyList(), StandardTestDispatcher(testScheduler))
        val text = formatRecommendationStatistics(RecommendationStatisticsReport(empty, empty))
        for (label in listOf("Regular duplicates", "Suspected duplicates", "By group recommendation", "By file role",
            "Keep one", "Keep multiple", "Review", "Protected", "Preferred", "Likely redundant", "Preserve",
            "Images", "Videos", "Documents", "Other", "0 files", "0.00 MB", "1,000,000 bytes", "overlap")) {
            assertTrue(text.contains(label), "Missing $label")
        }
    }

    @Test
    fun aiCoverageAndImpactShowZeroBucketsCurrentProfileAndAdvisoryScope() = runTest {
        val ai = aggregateAiRecommendationStatistics(emptyList(), emptyList(), emptyMap())
        val empty = aggregateRecommendationStatistics(emptyList()).copy(ai = ai)
        val context = OllamaSettings("http://ollama.test", "selected-model").context()
        val text = formatRecommendationStatistics(RecommendationStatisticsReport(empty, empty, context))
        for (label in listOf("AI classification coverage", "PREFERRED", "BACKUP", "PROTECTED", "UNKNOWN",
            "Missing classification", "0 / 0 directories", "AI recommendation impact",
            "Deterministic-only", "With cached AI", "New redundant candidates", "Withdrawn redundant candidates",
            "Changed group actions", "Changed file decisions", "Changed retained replacement",
            "selected-model", "prompt v${context.promptVersion}", "not verified reclaimable space",
            "same stored evidence", "wording")) {
            assertTrue(label in text, "Missing $label")
        }
        val unconfigured = formatRecommendationStatistics(RecommendationStatisticsReport(empty, empty))
        assertTrue("AI is not configured" in unconfigured)
        assertTrue("No saved labels are applied" in unconfigured)
    }
}
