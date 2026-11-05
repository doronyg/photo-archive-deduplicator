package com.doronyg.photoarchivededuplicator.recommendation

import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationContext
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
    val classificationContext: DirectoryClassificationContext? = null,
    val inventory: InventoryStatistics = InventoryStatistics(),
)

/** Database-only snapshot; never extracts EXIF or computes/saves retention assessments. */
internal class RecommendationStatisticsService(
    private val repository: DirectoryRepository,
    private val retention: RetentionScoreService,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val classificationContext: DirectoryClassificationContext? = null,
) {
    suspend fun load(): RecommendationStatisticsReport = withContext(dispatcher) {
        val regular = repository.duplicateCandidates()
        val suspected = repository.suspectedDuplicates()
        val inventory = aggregateInventoryStatistics(repository.eligibleFileCountsByExtension(),
            regular.map { it.files }, suspected.map { it.files }, dispatcher)
        RecommendationStatisticsReport(
            summarize(regular.map { it.files }, retention.stored(regular)),
            summarize(suspected.map { it.files }, retention.storedSuspected(suspected)),
            classificationContext,
            inventory,
        )
    }

    private suspend fun summarize(
        groups: List<List<FileRecord>>,
        assessment: RetentionAssessmentResult,
    ): RecommendationStatistics {
        val exif = repository.fileExif(groups.flatten().map { it.path }, currentOnly = true).associateBy { it.input.path }
        val classifications = classificationContext?.let { context ->
            repository.directoryClassifications(groups.flatten().map { it.directoryPath }.distinct(), context)
                .associateBy { it.directoryPath }
        }.orEmpty()
        val recommendations = recommendDuplicateGroups(groups, exif, assessment.files, dispatcher = dispatcher,
            directoryClassifications = classifications)
        val baseline = if (classifications.isEmpty()) recommendations else
            recommendDuplicateGroups(groups, exif, assessment.files, dispatcher = dispatcher)
        val currentGroups = groups.map { recommendations.getValue(it.first().path) }
        val baselineGroups = groups.map { baseline.getValue(it.first().path) }
        return aggregateRecommendationStatistics(currentGroups, dispatcher).copy(
            ai = aggregateAiRecommendationStatistics(baselineGroups, currentGroups, classifications, dispatcher))
    }
}
