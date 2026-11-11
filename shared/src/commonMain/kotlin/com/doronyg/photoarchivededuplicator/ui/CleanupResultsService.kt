package com.doronyg.photoarchivededuplicator.ui

import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationContext
import com.doronyg.photoarchivededuplicator.recommendation.RecommendationStatisticsReport
import com.doronyg.photoarchivededuplicator.recommendation.RecommendationStatisticsService
import com.doronyg.photoarchivededuplicator.retention.RetentionScoreService
import com.doronyg.photoarchivededuplicator.scan.DirectoryRepository
import com.doronyg.photoarchivededuplicator.scan.InventorySummary
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class CleanupResultsSnapshot(
    val inventory: InventorySummary,
    val analysis: RecommendationStatisticsReport,
)

/** Database-only loading; history remains the controller's already-loaded, active-Bin snapshot. */
internal class CleanupResultsService(
    private val repository: DirectoryRepository,
    retention: RetentionScoreService,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    classificationContext: DirectoryClassificationContext? = null,
) {
    private val statistics = RecommendationStatisticsService(repository, retention, dispatcher, classificationContext)

    suspend fun load(): CleanupResultsSnapshot = withContext(dispatcher) {
        // Serialize with scan batches, without retaining the database lane during aggregation.
        repository.scanning {
            CleanupResultsSnapshot(repository.summary(), statistics.load())
        }
    }
}
