package com.doronyg.photoarchivededuplicator.scan

import com.doronyg.photoarchivededuplicator.recommendation.DuplicateGroupRecommendation
import com.doronyg.photoarchivededuplicator.recommendation.RecommendationAction
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.Path

data class SuspectedDuplicateFilter(
    val root: Path? = null,
    val filenamePattern: String = "",
    val recommendation: RecommendationAction? = null,
) {
    init {
        root?.inventoryPath()
    }
}

/** Match one member against folder AND filename, but retain its complete group and member order. */
internal suspend fun filterSuspectedDuplicateCandidates(
    groups: List<SuspectedDuplicateGroup>,
    filter: SuspectedDuplicateFilter,
    recommendations: Map<Path, DuplicateGroupRecommendation> = emptyMap(),
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): List<SuspectedDuplicateGroup> = withContext(dispatcher) {
    val context = currentCoroutineContext()
    val filename = DuplicateCandidateFilter(filenamePattern = filter.filenamePattern)
    groups.filter { group ->
        context.ensureActive()
        val actionMatches = filter.recommendation == null ||
            group.files.firstOrNull()?.let { recommendations[it.path]?.action } == filter.recommendation
        actionMatches && group.files.any { file ->
            context.ensureActive()
            (filter.root == null || file.directoryPath.isInTree(filter.root)) && filename.matches(file.name)
        }
    }
}
