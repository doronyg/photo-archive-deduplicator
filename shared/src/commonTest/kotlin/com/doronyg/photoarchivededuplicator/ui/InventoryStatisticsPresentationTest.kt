package com.doronyg.photoarchivededuplicator.ui

import com.doronyg.photoarchivededuplicator.recommendation.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class InventoryStatisticsPresentationTest {
    @Test
    fun percentagesHandleEmptyInventoryRoundingAndLargeCounts() {
        assertEquals("0.00%", formatInventoryDuplicatePercentage(InventoryFileCounts()))
        assertEquals("33.33%", formatInventoryDuplicatePercentage(InventoryFileCounts(3, 1)))
        assertEquals("66.67%", formatInventoryDuplicatePercentage(InventoryFileCounts(3, 2)))
        assertEquals("100.00%", formatInventoryDuplicatePercentage(InventoryFileCounts(9, 9)))
        assertEquals("100.00%", formatInventoryDuplicatePercentage(InventoryFileCounts(Long.MAX_VALUE, Long.MAX_VALUE)))
        assertEquals("50.00%", formatInventoryDuplicatePercentage(InventoryFileCounts(Long.MAX_VALUE, Long.MAX_VALUE / 2)))
    }

    @Test
    fun statisticsShowTotalsDuplicateCountsAndPercentagesForAllRegisteredTypes() = runTest {
        val empty = aggregateRecommendationStatistics(emptyList())
        val inventory = aggregateInventoryStatistics(mapOf("jpg" to 4L, "pdf" to 2L, "mp4" to 1L, null to 1L),
            emptyList(), emptyList())
        val text = formatRecommendationStatistics(RecommendationStatisticsReport(empty, empty, inventory = inventory))
        for (row in listOf("All: 8 files | 0 possible duplicates (0.00%)",
            "Images: 4 files | 0 possible duplicates (0.00%)",
            "Documents: 2 files | 0 possible duplicates (0.00%)",
            "Videos: 1 file | 0 possible duplicates (0.00%)",
            "Other: 1 file | 0 possible duplicates (0.00%)")) {
            assertTrue(row in text, "Missing $row")
        }
        assertTrue(text.indexOf("Inventory files") < text.indexOf("Regular duplicates"))
        assertTrue("including retained copies" in text)
    }
}
