package com.doronyg.photoarchivededuplicator.scan

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class DuplicateSortCriterion { NAME, FILE_SIZE, DUPLICATE_COUNT, EARLIEST_CREATED }
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

private data class SortableCandidate(val group: DuplicateCandidateGroup, val earliestCreated: Long?)

/** Reorders groups, never their files. Unknown creation dates stay last in either direction. */
internal suspend fun sortDuplicateCandidates(
    groups: List<DuplicateCandidateGroup>,
    rules: List<DuplicateSortRule>,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): List<DuplicateCandidateGroup> = withContext(dispatcher) {
    val enabled = rules.filter { it.enabled }
    if (enabled.isEmpty()) return@withContext groups.toList()
    val context = currentCoroutineContext()
    val needCreation = enabled.any { it.criterion == DuplicateSortCriterion.EARLIEST_CREATED }
    val candidates = groups.map { group ->
        context.ensureActive()
        var earliest: Long? = null
        if (needCreation) {
            for (file in group.files) {
                context.ensureActive()
                val date = file.createdAt
                if (date != null && (earliest == null || date < earliest)) earliest = date
            }
        }
        SortableCandidate(group, earliest)
    }
    candidates.sortedWith { a, b ->
        context.ensureActive()
        var comparison = 0
        for (rule in enabled) {
            if (rule.criterion == DuplicateSortCriterion.EARLIEST_CREATED &&
                (a.earliestCreated == null || b.earliestCreated == null)) {
                comparison = when {
                    a.earliestCreated == b.earliestCreated -> 0
                    a.earliestCreated == null -> 1
                    else -> -1
                }
            } else {
                comparison = when (rule.criterion) {
                    DuplicateSortCriterion.NAME -> a.group.name.compareTo(b.group.name)
                    DuplicateSortCriterion.FILE_SIZE -> a.group.sizeBytes.compareTo(b.group.sizeBytes)
                    DuplicateSortCriterion.DUPLICATE_COUNT -> a.group.files.size.compareTo(b.group.files.size)
                    DuplicateSortCriterion.EARLIEST_CREATED -> compareValues(a.earliestCreated, b.earliestCreated)
                }
                if (rule.direction == SortDirection.DESCENDING) comparison = -comparison
            }
            if (comparison != 0) break
        }
        if (comparison != 0) comparison
        else a.group.name.compareTo(b.group.name).takeIf { it != 0 }
            ?: a.group.sizeBytes.compareTo(b.group.sizeBytes)
    }.map {
        context.ensureActive()
        it.group
    }
}
