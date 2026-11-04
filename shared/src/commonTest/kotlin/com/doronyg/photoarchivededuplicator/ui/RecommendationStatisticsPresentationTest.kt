package com.doronyg.photoarchivededuplicator.ui

import com.doronyg.photoarchivededuplicator.UiChoices
import com.doronyg.photoarchivededuplicator.UiTab
import com.doronyg.photoarchivededuplicator.decodeUiChoices
import com.doronyg.photoarchivededuplicator.encodeUiChoices
import com.doronyg.photoarchivededuplicator.recommendation.RecommendationStatisticsReport
import com.doronyg.photoarchivededuplicator.recommendation.aggregateRecommendationStatistics
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
}
