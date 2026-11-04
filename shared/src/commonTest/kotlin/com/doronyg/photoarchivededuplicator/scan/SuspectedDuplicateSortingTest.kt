package com.doronyg.photoarchivededuplicator.scan

import com.doronyg.photoarchivededuplicator.recommendation.DuplicateRecommendationEngine
import com.doronyg.photoarchivededuplicator.recommendation.EvidenceStrength
import com.doronyg.photoarchivededuplicator.recommendation.RecommendationAction
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
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
class SuspectedDuplicateSortingTest {
    private fun group(
        names: List<String>,
        size: Long = 120_000,
        dates: List<Long?> = names.map { null },
        capture: String = "2023:02:18 12:44:25",
        offset: String? = null,
    ): SuspectedDuplicateGroup = SuspectedDuplicateGroup(size, capture, offset, names.mapIndexed { index, name ->
        val directory = "/archive/${names.first()}/$size/$capture/${offset ?: "unknown"}/$index".toPath()
        FileRecord(directory / name, directory, name, "jpg", size, null, 1, dates[index])
    })

    private fun recommendation(action: RecommendationAction) =
        DuplicateRecommendationEngine().recommend(emptyList(), emptyMap(), emptyMap()).copy(
            action = action, evidenceStrength = EvidenceStrength.SUPPORTED, reasonCodes = emptySet(), explanation = "Fixture")

    @Test
    fun filenameUsesSmallestCaseSensitiveMemberRegardlessOfRetentionOrder() = runTest {
        val first = group(listOf("z.jpg", "A.jpg"))
        val second = group(listOf("b.jpg", "c.jpg"))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val rule = DuplicateSortRule(DuplicateSortCriterion.NAME)
        val sorted = sortSuspectedDuplicateCandidates(listOf(second, first), listOf(rule), dispatcher)
        assertEquals(listOf(first, second), sorted)
        assertSame(first.files, sorted.first().files, "Group sorting must not reorder retention-ranked members")
        val reversedMembers = first.copy(files = first.files.reversed())
        assertEquals(listOf(reversedMembers, second),
            sortSuspectedDuplicateCandidates(listOf(second, reversedMembers), listOf(rule), dispatcher))
        assertEquals(listOf(second, first),
            sortSuspectedDuplicateCandidates(listOf(first, second),
                listOf(rule.copy(direction = SortDirection.DESCENDING)), dispatcher))
    }

    @Test
    fun sizeCountAndCreationSupportBothDirectionsAndUnknownCreationStaysLast() = runTest {
        val small = group(listOf("z.jpg", "y.jpg", "x.jpg"), 120_000, listOf(null, 50, 10))
        val large = group(listOf("a.jpg", "b.jpg"), Long.MAX_VALUE, listOf(30, 20))
        val unknown = group(listOf("u.jpg", "v.jpg"), 150_000)
        val dispatcher = StandardTestDispatcher(testScheduler)
        for ((criterion, ascending) in listOf(
            DuplicateSortCriterion.FILE_SIZE to listOf(small, large),
            DuplicateSortCriterion.DUPLICATE_COUNT to listOf(large, small),
            DuplicateSortCriterion.EARLIEST_CREATED to listOf(small, large),
        )) {
            val rule = DuplicateSortRule(criterion)
            assertEquals(ascending, sortSuspectedDuplicateCandidates(listOf(large, small), listOf(rule), dispatcher))
            assertEquals(ascending.reversed(), sortSuspectedDuplicateCandidates(listOf(small, large),
                listOf(rule.copy(direction = SortDirection.DESCENDING)), dispatcher))
        }
        for (direction in SortDirection.entries) {
            assertEquals(unknown, sortSuspectedDuplicateCandidates(listOf(unknown, small, large),
                listOf(DuplicateSortRule(DuplicateSortCriterion.EARLIEST_CREATED, direction = direction)), dispatcher).last())
        }
    }

    @Test
    fun priorityAndDisabledRulesUseTheSameSemanticsAsRegularDuplicates() = runTest {
        val a = group(listOf("a.jpg", "b.jpg"), 200_000)
        val b = group(listOf("c.jpg", "d.jpg", "e.jpg"), 200_000)
        val c = group(listOf("f.jpg", "g.jpg", "h.jpg", "i.jpg"), 120_000)
        val size = DuplicateSortRule(DuplicateSortCriterion.FILE_SIZE, direction = SortDirection.DESCENDING)
        val count = DuplicateSortRule(DuplicateSortCriterion.DUPLICATE_COUNT, direction = SortDirection.DESCENDING)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val input = listOf(a, c, b)
        assertEquals(listOf(b, a, c), sortSuspectedDuplicateCandidates(input, listOf(size, count), dispatcher))
        assertEquals(listOf(c, b, a), sortSuspectedDuplicateCandidates(input, listOf(count, size), dispatcher))
        assertEquals(listOf(c, b, a), sortSuspectedDuplicateCandidates(input, listOf(size.copy(enabled = false), count), dispatcher))
        assertEquals(input, sortSuspectedDuplicateCandidates(input,
            defaultDuplicateSortRules().map { it.copy(enabled = false) }, dispatcher))
        assertEquals(emptyList(), sortSuspectedDuplicateCandidates(emptyList(), defaultDuplicateSortRules(), dispatcher))
    }

    @Test
    fun recommendationTypesHaveExplicitOrderAndUnavailableResultsAlwaysStayLast() = runTest {
        val actions = listOf(RecommendationAction.KEEP_ONE, RecommendationAction.KEEP_MULTIPLE,
            RecommendationAction.REVIEW, RecommendationAction.PROTECTED)
        val groups = actions.mapIndexed { index, _ -> group(listOf("$index.jpg", "$index-copy.jpg")) }
        val unavailable = group(listOf("unknown.jpg", "unknown-copy.jpg"))
        val recommendations = groups.zip(actions).flatMap { (group, action) ->
            group.files.map { it.path to recommendation(action) }
        }.toMap()
        val input = listOf(unavailable) + groups.reversed()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val rule = DuplicateSortRule(DuplicateSortCriterion.RECOMMENDATION)
        assertEquals(groups + unavailable, sortSuspectedDuplicateCandidates(input, listOf(rule), dispatcher, recommendations))
        assertEquals(groups.reversed() + unavailable, sortSuspectedDuplicateCandidates(input,
            listOf(rule.copy(direction = SortDirection.DESCENDING)), dispatcher, recommendations))
    }

    @Test
    fun regularDuplicatesSupportTheSameRecommendationCriterionAndSecondaryPriorities() = runTest {
        val a = group(listOf("a.jpg", "a-copy.jpg"), 120_000)
        val b = group(listOf("b.jpg", "b-copy.jpg"), 200_000)
        val c = group(listOf("c.jpg", "c-copy.jpg"), 300_000)
        val regular = listOf(a, b, c).map { DuplicateCandidateGroup(it.files.first().name, it.sizeBytes, it.files) }
        val recommendations = (a.files + b.files).associate { it.path to recommendation(RecommendationAction.KEEP_ONE) } +
            c.files.associate { it.path to recommendation(RecommendationAction.REVIEW) }
        val rules = listOf(DuplicateSortRule(DuplicateSortCriterion.RECOMMENDATION),
            DuplicateSortRule(DuplicateSortCriterion.FILE_SIZE, direction = SortDirection.DESCENDING))
        val dispatcher = StandardTestDispatcher(testScheduler)
        assertEquals(listOf(regular[1], regular[0], regular[2]),
            sortDuplicateCandidates(regular.reversed(), rules, dispatcher, recommendations))
        assertEquals(listOf(b, a, c), sortSuspectedDuplicateCandidates(listOf(c, a, b), rules, dispatcher, recommendations))
    }

    @Test
    fun exactSortTiesUseCaptureOffsetAndPathDeterministically() = runTest {
        val early = group(listOf("a.jpg", "b.jpg"), capture = "2023:02:18 12:44:25")
        val late = group(listOf("a.jpg", "b.jpg"), capture = "2023:02:18 12:44:26")
        val knownOffset = early.copy(offsetOriginal = "+01:00")
        val rule = listOf(DuplicateSortRule(DuplicateSortCriterion.DUPLICATE_COUNT))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val expected = listOf(early, knownOffset, late)
        assertEquals(expected, sortSuspectedDuplicateCandidates(expected.reversed(), rule, dispatcher))
        assertEquals(expected, sortSuspectedDuplicateCandidates(listOf(knownOffset, late, early), rule, dispatcher))
        val otherPath = early.copy(files = early.files.map {
            val directory = "/AAA".toPath()
            it.copy(path = directory / it.name, directoryPath = directory)
        })
        assertEquals(listOf(otherPath, early),
            sortSuspectedDuplicateCandidates(listOf(early, otherPath), rule, dispatcher))
    }

    @Test
    fun sortingDispatchesOffCallerAndCancellationDoesNotPublish() = runTest {
        val groups = listOf(group(listOf("z.jpg", "y.jpg")), group(listOf("a.jpg", "b.jpg")))
        val dispatcher = StandardTestDispatcher(testScheduler)
        var published: List<SuspectedDuplicateGroup>? = null
        val obsolete = async(start = CoroutineStart.UNDISPATCHED) {
            published = sortSuspectedDuplicateCandidates(groups, defaultDuplicateSortRules(), dispatcher)
        }
        assertFalse(obsolete.isCompleted)
        obsolete.cancel()
        runCurrent()
        obsolete.join()
        assertEquals(null, published)
    }
}
