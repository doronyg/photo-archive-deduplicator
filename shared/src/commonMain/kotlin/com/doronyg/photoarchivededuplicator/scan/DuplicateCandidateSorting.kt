package com.doronyg.photoarchivededuplicator.scan

import com.doronyg.photoarchivededuplicator.recommendation.DuplicateGroupRecommendation
import com.doronyg.photoarchivededuplicator.recommendation.RecommendationAction
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.Path

enum class DuplicateSortCriterion { NAME, FILE_SIZE, DUPLICATE_COUNT, EARLIEST_CREATED, RECOMMENDATION }
enum class SortDirection { ASCENDING, DESCENDING }

data class DuplicateSortRule(
    val criterion: DuplicateSortCriterion,
    val enabled: Boolean = true,
    val direction: SortDirection = SortDirection.ASCENDING,
)

fun defaultDuplicateSortRules(): List<DuplicateSortRule> = listOf(
    DuplicateSortRule(DuplicateSortCriterion.NAME),
    DuplicateSortRule(DuplicateSortCriterion.FILE_SIZE, enabled = false, direction = SortDirection.DESCENDING),
    DuplicateSortRule(DuplicateSortCriterion.DUPLICATE_COUNT, enabled = false, direction = SortDirection.DESCENDING),
    DuplicateSortRule(DuplicateSortCriterion.EARLIEST_CREATED, enabled = false),
    DuplicateSortRule(DuplicateSortCriterion.RECOMMENDATION, enabled = false),
)

internal fun moveDuplicateSortRule(
    rules: List<DuplicateSortRule>,
    criterion: DuplicateSortCriterion,
    offset: Int,
): List<DuplicateSortRule> {
    require(offset == -1 || offset == 1) { "Move one sort priority at a time" }
    val index = rules.indexOfFirst { it.criterion == criterion }
    require(index >= 0) { "Sort criterion is not present: $criterion" }
    val destination = index + offset
    if (destination !in rules.indices) return rules
    return rules.toMutableList().apply {
        this[index] = rules[destination]
        this[destination] = rules[index]
    }
}

private data class GroupSortData(
    val name: String?,
    val sizeBytes: Long,
    val files: List<FileRecord>,
    val capturedAt: String = "",
    val offsetOriginal: String = "",
)

private data class SortableCandidate<T>(
    val group: T,
    val data: GroupSortData,
    val name: String,
    val earliestCreated: Long?,
    val recommendationOrder: Int?,
    val firstPath: String,
)

/** Reorders groups, never their files. Unknown creation dates stay last in either direction. */
internal suspend fun sortDuplicateCandidates(
    groups: List<DuplicateCandidateGroup>,
    rules: List<DuplicateSortRule>,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    recommendations: Map<Path, DuplicateGroupRecommendation> = emptyMap(),
): List<DuplicateCandidateGroup> = sortCandidateGroups(groups, rules, dispatcher, recommendations) {
    GroupSortData(it.name, it.sizeBytes, it.files)
}

internal suspend fun sortSuspectedDuplicateCandidates(
    groups: List<SuspectedDuplicateGroup>,
    rules: List<DuplicateSortRule>,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    recommendations: Map<Path, DuplicateGroupRecommendation> = emptyMap(),
): List<SuspectedDuplicateGroup> = sortCandidateGroups(groups, rules, dispatcher, recommendations) {
    GroupSortData(null, it.sizeBytes, it.files, it.capturedAt, it.offsetOriginal.orEmpty())
}

private suspend fun <T> sortCandidateGroups(
    groups: List<T>,
    rules: List<DuplicateSortRule>,
    dispatcher: CoroutineDispatcher,
    recommendations: Map<Path, DuplicateGroupRecommendation>,
    describe: (T) -> GroupSortData,
): List<T> = withContext(dispatcher) {
    val enabled = rules.filter { it.enabled }
    if (enabled.isEmpty()) return@withContext groups.toList()
    val context = currentCoroutineContext()
    val needCreation = enabled.any { it.criterion == DuplicateSortCriterion.EARLIEST_CREATED }
    val needRecommendation = enabled.any { it.criterion == DuplicateSortCriterion.RECOMMENDATION }
    val candidates = groups.map { group ->
        context.ensureActive()
        val data = describe(group)
        var name = data.name
        var firstPath: String? = null
        var earliest: Long? = null
        for (file in data.files) {
            context.ensureActive()
            if (data.name == null && (name == null || file.name < name)) name = file.name
            val path = file.path.toString()
            if (firstPath == null || path < firstPath) firstPath = path
            val date = file.createdAt
            if (needCreation && date != null && (earliest == null || date < earliest)) earliest = date
        }
        val action = if (needRecommendation) data.files.firstOrNull()?.let { recommendations[it.path]?.action } else null
        SortableCandidate(group, data, name.orEmpty(), earliest, action?.sortOrder(), firstPath.orEmpty())
    }
    candidates.sortedWith { a, b ->
        context.ensureActive()
        var comparison = 0
        for (rule in enabled) {
            comparison = when (rule.criterion) {
                DuplicateSortCriterion.NAME -> compareAvailable(a.name, b.name, rule.direction)
                DuplicateSortCriterion.FILE_SIZE -> compareAvailable(a.data.sizeBytes, b.data.sizeBytes, rule.direction)
                DuplicateSortCriterion.DUPLICATE_COUNT -> compareAvailable(a.data.files.size, b.data.files.size, rule.direction)
                DuplicateSortCriterion.EARLIEST_CREATED -> compareAvailable(a.earliestCreated, b.earliestCreated, rule.direction)
                DuplicateSortCriterion.RECOMMENDATION -> compareAvailable(a.recommendationOrder, b.recommendationOrder, rule.direction)
            }
            if (comparison != 0) break
        }
        if (comparison != 0) comparison
        else compareValuesBy(a, b, { it.name }, { it.data.sizeBytes }, { it.data.capturedAt },
            { it.data.offsetOriginal }, { it.firstPath })
    }.map {
        context.ensureActive()
        it.group
    }
}

private fun <T : Comparable<T>> compareAvailable(a: T?, b: T?, direction: SortDirection): Int {
    if (a == null || b == null) return when {
        a == b -> 0
        a == null -> 1
        else -> -1
    }
    val comparison = a.compareTo(b)
    return if (direction == SortDirection.DESCENDING) -comparison else comparison
}

private fun RecommendationAction.sortOrder(): Int = when (this) {
    RecommendationAction.KEEP_ONE -> 0
    RecommendationAction.KEEP_MULTIPLE -> 1
    RecommendationAction.REVIEW -> 2
    RecommendationAction.PROTECTED -> 3
}
