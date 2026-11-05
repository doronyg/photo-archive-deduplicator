package com.doronyg.photoarchivededuplicator.recommendation

import com.doronyg.photoarchivededuplicator.scan.DuplicateFileType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.Path

internal enum class StatisticsFileType(private val filter: DuplicateFileType?) {
    IMAGES(DuplicateFileType.IMAGES),
    VIDEOS(DuplicateFileType.VIDEOS),
    DOCUMENTS(DuplicateFileType.DOCUMENTS),
    OTHER(null);

    companion object {
        fun forFilename(name: String): StatisticsFileType =
            entries.firstOrNull { it.filter?.matches(name) == true } ?: OTHER
    }
}

internal data class FileStatistics(val fileCount: Long = 0, val sizeBytes: Long = 0)

internal data class RecommendationStatisticsBucket(
    val totals: FileStatistics,
    val byFileType: Map<StatisticsFileType, FileStatistics>,
)

internal data class RecommendationStatistics(
    val groupCount: Long,
    val totals: FileStatistics,
    val byAction: Map<RecommendationAction, RecommendationStatisticsBucket>,
    val byRole: Map<FileRecommendationRole, RecommendationStatisticsBucket>,
    val byFileType: Map<StatisticsFileType, FileStatistics>,
    val groupCounts: Map<RecommendationAction, Long>,
    val ai: AiRecommendationStatistics? = null,
)

/** Accepts one recommendation per group within a single, non-overlapping cohort. */
internal suspend fun aggregateRecommendationStatistics(
    groups: List<DuplicateGroupRecommendation>,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): RecommendationStatistics = withContext(dispatcher) {
    val context = currentCoroutineContext()
    val paths = mutableSetOf<Path>()
    val totals = StatisticsAccumulator()
    val byAction = RecommendationAction.entries.associateWith { StatisticsAccumulator() }
    val byRole = FileRecommendationRole.entries.associateWith { StatisticsAccumulator() }
    val groupCounts = RecommendationAction.entries.associateWith { 0L }.toMutableMap()
    var groupCount = 0L

    for (group in groups) {
        context.ensureActive()
        groupCount = checkedAdd(groupCount, 1)
        groupCounts[group.action] = checkedAdd(groupCounts.getValue(group.action), 1)
        for (recommendation in group.files.values) {
            context.ensureActive()
            val file = recommendation.file
            require(file.sizeBytes >= 0) { "File size must not be negative: ${file.path}" }
            require(paths.add(file.path)) { "Repeated file path in recommendation groups: ${file.path}" }
            val type = StatisticsFileType.forFilename(file.name)
            totals.add(type, file.sizeBytes)
            byAction.getValue(group.action).add(type, file.sizeBytes)
            byRole.getValue(recommendation.role).add(type, file.sizeBytes)
        }
    }
    context.ensureActive()
    val totalBucket = totals.snapshot()
    RecommendationStatistics(
        groupCount, totalBucket.totals,
        byAction.mapValues { it.value.snapshot() },
        byRole.mapValues { it.value.snapshot() },
        totalBucket.byFileType, groupCounts.toMap(),
    )
}

private class StatisticsAccumulator {
    private var totals = FileStatistics()
    private val byFileType = StatisticsFileType.entries.associateWith { FileStatistics() }.toMutableMap()

    fun add(type: StatisticsFileType, sizeBytes: Long) {
        totals = totals.addFile(sizeBytes)
        byFileType[type] = byFileType.getValue(type).addFile(sizeBytes)
    }

    fun snapshot() = RecommendationStatisticsBucket(totals, byFileType.toMap())
}

internal fun FileStatistics.addFile(sizeBytes: Long) =
    FileStatistics(checkedAdd(fileCount, 1), checkedAdd(this.sizeBytes, sizeBytes))

internal fun checkedAdd(left: Long, right: Long): Long {
    if (left > Long.MAX_VALUE - right) throw ArithmeticException("Recommendation statistics overflow")
    return left + right
}
