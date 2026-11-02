package com.doronyg.photoarchivededuplicator.scan

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class DuplicateCandidateSortingTest {
    private fun group(name: String, size: Long, dates: List<Long?> = listOf(null, null)) =
        DuplicateCandidateGroup(name, size, dates.mapIndexed { index, date ->
            val directory = "/archive/$index".toPath()
            FileRecord(directory / name, directory, name, null, size, null, 1, date)
        })

    @Test
    fun sizeAndCountSupportBothDirectionsWithoutChangingMembers() = runTest(timeout = 10.seconds) {
        val small = group("z.jpg", 1, listOf(null, null, null))
        val large = group("a.jpg", Long.MAX_VALUE)
        val input = listOf(small, large)
        val dispatcher = StandardTestDispatcher(testScheduler)
        for ((criterion, ascending) in listOf(
            DuplicateSortCriterion.FILE_SIZE to listOf(small, large),
            DuplicateSortCriterion.DUPLICATE_COUNT to listOf(large, small),
        )) {
            assertEquals(ascending, sortDuplicateCandidates(input,
                listOf(DuplicateSortRule(criterion)), dispatcher))
            assertEquals(ascending.reversed(), sortDuplicateCandidates(input,
                listOf(DuplicateSortRule(criterion, direction = SortDirection.DESCENDING)), dispatcher))
        }
        assertEquals(listOf(small, large), input)
        assertEquals(listOf(null, null, null), small.files.map { it.createdAt })
    }

    @Test
    fun lexicographicSortIsCaseSensitiveAndSupportsBothDirections() = runTest(timeout = 10.seconds) {
        val input = listOf(group("b.jpg", 1), group("a.jpg", 1), group("A.jpg", 1))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val ascending = sortDuplicateCandidates(input, listOf(DuplicateSortRule(DuplicateSortCriterion.NAME)), dispatcher)
        assertEquals(listOf("A.jpg", "a.jpg", "b.jpg"), ascending.map { it.name })
        assertEquals(ascending.reversed(), sortDuplicateCandidates(input,
            listOf(DuplicateSortRule(DuplicateSortCriterion.NAME, direction = SortDirection.DESCENDING)), dispatcher))
    }

    @Test
    fun leftmostEnabledCriterionWinsAndLaterCriteriaBreakTies() = runTest(timeout = 10.seconds) {
        val a = group("a.jpg", 100, listOf(null, null))
        val b = group("b.jpg", 100, listOf(null, null, null))
        val c = group("c.jpg", 1, listOf(null, null, null, null))
        val input = listOf(a, c, b)
        val size = DuplicateSortRule(DuplicateSortCriterion.FILE_SIZE, direction = SortDirection.DESCENDING)
        val count = DuplicateSortRule(DuplicateSortCriterion.DUPLICATE_COUNT, direction = SortDirection.DESCENDING)
        val dispatcher = StandardTestDispatcher(testScheduler)
        assertEquals(listOf(b, a, c), sortDuplicateCandidates(input, listOf(size, count), dispatcher))
        assertEquals(listOf(c, b, a), sortDuplicateCandidates(input, listOf(count, size), dispatcher))
        assertEquals(listOf(c, b, a), sortDuplicateCandidates(input, listOf(size.copy(enabled = false), count), dispatcher))
    }

    @Test
    fun creationUsesEarliestKnownMemberNotFirstListedAndUnknownsAreAlwaysLast() = runTest(timeout = 10.seconds) {
        val early = group("early.jpg", 1, listOf(null, 50, 10))
        val late = group("late.jpg", 1, listOf(30, 20))
        val epoch = group("epoch.jpg", 1, listOf(0, null))
        val unknown = group("unknown.jpg", 1)
        val input = listOf(unknown, late, early, epoch)
        val dispatcher = StandardTestDispatcher(testScheduler)
        assertEquals(listOf(epoch, early, late, unknown), sortDuplicateCandidates(input,
            listOf(DuplicateSortRule(DuplicateSortCriterion.EARLIEST_CREATED)), dispatcher))
        assertEquals(listOf(late, early, epoch, unknown), sortDuplicateCandidates(input,
            listOf(DuplicateSortRule(DuplicateSortCriterion.EARLIEST_CREATED, direction = SortDirection.DESCENDING)), dispatcher))
    }

    @Test
    fun tiedGroupsHaveDeterministicNameThenSizeOrder() = runTest(timeout = 10.seconds) {
        val a = group("a.jpg", 1)
        val b = group("a.jpg", 2)
        val c = group("c.jpg", 1)
        val rule = listOf(DuplicateSortRule(DuplicateSortCriterion.DUPLICATE_COUNT))
        val dispatcher = StandardTestDispatcher(testScheduler)
        assertEquals(listOf(a, b, c), sortDuplicateCandidates(listOf(c, b, a), rule, dispatcher))
        assertEquals(listOf(a, b, c), sortDuplicateCandidates(listOf(b, a, c), rule, dispatcher))
    }

    @Test
    fun allDisabledPreservesInputOrderAndEmptyInputIsSupported() = runTest(timeout = 10.seconds) {
        val input = listOf(group("z.jpg", 1), group("a.jpg", 100))
        val rules = defaultDuplicateSortRules().map { it.copy(enabled = false) }
        val dispatcher = StandardTestDispatcher(testScheduler)
        assertEquals(input, sortDuplicateCandidates(input, rules, dispatcher))
        assertEquals(emptyList(), sortDuplicateCandidates(emptyList(), rules, dispatcher))
        assertEquals(listOf("a.jpg", "z.jpg"),
            sortDuplicateCandidates(input, defaultDuplicateSortRules(), dispatcher).map { it.name })
    }

    @Test
    fun sortingDispatchesAwayFromCallerAndCancellationPreventsPublication() = runTest(timeout = 10.seconds) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val input = listOf(group("z.jpg", 1), group("a.jpg", 2))
        var published: List<DuplicateCandidateGroup>? = null
        val obsolete = async(start = CoroutineStart.UNDISPATCHED) {
            published = sortDuplicateCandidates(input, defaultDuplicateSortRules(), dispatcher)
        }
        assertFalse(obsolete.isCompleted, "Sorting must dispatch rather than run inline on the caller")
        obsolete.cancel()
        runCurrent()
        obsolete.join()
        assertEquals(null, published)
        assertEquals(listOf("a.jpg", "z.jpg"),
            sortDuplicateCandidates(input, defaultDuplicateSortRules(), dispatcher).map { it.name })
    }

    @Test
    fun priorityArrowsSwapAdjacentRulesWithoutChangingTheirSettings() {
        val rules = defaultDuplicateSortRules()
        val moved = moveDuplicateSortRule(rules, rules[1].criterion, -1)
        assertEquals(listOf(rules[1], rules[0]) + rules.drop(2), moved)
        assertEquals(rules, moveDuplicateSortRule(moved, rules[1].criterion, 1))
        assertEquals(rules, moveDuplicateSortRule(rules, rules.first().criterion, -1))
        assertEquals(rules, moveDuplicateSortRule(rules, rules.last().criterion, 1))
    }
}
