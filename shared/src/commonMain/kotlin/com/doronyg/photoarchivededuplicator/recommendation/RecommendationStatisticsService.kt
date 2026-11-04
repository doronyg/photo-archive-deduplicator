package com.doronyg.photoarchivededuplicator.recommendation

import com.doronyg.photoarchivededuplicator.retention.RetentionAssessmentResult
import com.doronyg.photoarchivededuplicator.retention.RetentionScoreService
import com.doronyg.photoarchivededuplicator.scan.DirectoryRepository
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class RecommendationStatisticsReport(
    val regular: RecommendationStatistics,
    val suspected: RecommendationStatistics,
)

/** Database-only snapshot; never extracts EXIF or computes/saves retention assessments. */
internal class RecommendationStatisticsService(
    private val repository: DirectoryRepository,
    private val retention: RetentionScoreService,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend fun load(): RecommendationStatisticsReport = withContext(dispatcher) {
        RecommendationStatisticsReport(regularStatistics(), suspectedStatistics())
    }

    private suspend fun regularStatistics(): RecommendationStatistics {
        val groups = repository.duplicateCandidates()
        return summarize(groups.map { it.files }, retention.stored(groups))
    }

    private suspend fun suspectedStatistics(): RecommendationStatistics {
        val groups = repository.suspectedDuplicates()
        return summarize(groups.map { it.files }, retention.storedSuspected(groups))
    }

    private suspend fun summarize(
        groups: List<List<FileRecord>>,
        assessment: RetentionAssessmentResult,
    ): RecommendationStatistics {
        val exif = repository.fileExif(groups.flatten().map { it.path }, currentOnly = true).associateBy { it.input.path }
        val recommendations = recommendDuplicateGroups(groups, exif, assessment.files, dispatcher = dispatcher)
        return aggregateRecommendationStatistics(groups.map { recommendations.getValue(it.first().path) }, dispatcher)
    }
}
