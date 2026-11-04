package com.doronyg.photoarchivededuplicator.recommendation

import com.doronyg.photoarchivededuplicator.scan.FileRecord
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RecommendationStatisticsTest {
    private fun file(name: String, size: Long, role: FileRecommendationRole): FileRecommendation {
        val directory = "/Pictures".toPath()
        return FileRecommendation(
            FileRecord(directory / name, directory, name, null, size, 1, 2),
            role, name, emptySet(), "",
        )
    }

    private fun group(
        action: RecommendationAction,
        vararg files: FileRecommendation,
    ) = DuplicateGroupRecommendation(action, EvidenceStrength.STRONG, emptySet(), "",
        files.associateBy { it.file.path })

    @Test
    fun groupActionsIncludeEveryMemberWhileRolesCountMembersIndependently() = runTest {
        val result = aggregateRecommendationStatistics(listOf(
            group(RecommendationAction.KEEP_ONE,
                file("original.JPG", 100, FileRecommendationRole.PREFERRED),
                file("copy.ARW", 200, FileRecommendationRole.REDUNDANT_CANDIDATE)),
            group(RecommendationAction.KEEP_MULTIPLE,
                file("movie.MOV", 300, FileRecommendationRole.PRESERVE)),
            group(RecommendationAction.REVIEW,
                file("notes.PDF", 400, FileRecommendationRole.REVIEW)),
            group(RecommendationAction.PROTECTED,
                file("archive.bin", 500, FileRecommendationRole.PRESERVE)),
        ), StandardTestDispatcher(testScheduler))

        assertEquals(4L, result.groupCount)
        assertEquals(FileStatistics(5, 1_500), result.totals)
        assertEquals(RecommendationAction.entries.associateWith { 1L }, result.groupCounts)
        assertEquals(FileStatistics(2, 300), result.byAction.getValue(RecommendationAction.KEEP_ONE).totals)
        assertEquals(FileStatistics(1, 300), result.byAction.getValue(RecommendationAction.KEEP_MULTIPLE).totals)
        assertEquals(FileStatistics(1, 400), result.byAction.getValue(RecommendationAction.REVIEW).totals)
        assertEquals(FileStatistics(1, 500), result.byAction.getValue(RecommendationAction.PROTECTED).totals)
        assertEquals(FileStatistics(1, 100), result.byRole.getValue(FileRecommendationRole.PREFERRED).totals)
        assertEquals(FileStatistics(1, 200), result.byRole.getValue(FileRecommendationRole.REDUNDANT_CANDIDATE).totals)
        assertEquals(FileStatistics(2, 800), result.byRole.getValue(FileRecommendationRole.PRESERVE).totals)
        assertEquals(FileStatistics(1, 400), result.byRole.getValue(FileRecommendationRole.REVIEW).totals)
        assertEquals(FileStatistics(2, 300),
            result.byAction.getValue(RecommendationAction.KEEP_ONE).byFileType.getValue(StatisticsFileType.IMAGES))
        assertEquals(FileStatistics(1, 300),
            result.byRole.getValue(FileRecommendationRole.PRESERVE).byFileType.getValue(StatisticsFileType.VIDEOS))
        assertEquals(FileStatistics(1, 500),
            result.byRole.getValue(FileRecommendationRole.PRESERVE).byFileType.getValue(StatisticsFileType.OTHER))
    }

    @Test
    fun mixedTypesUseCaseInsensitiveInventoryCategoriesAndUnknownsAreOther() = runTest {
        val result = aggregateRecommendationStatistics(listOf(group(RecommendationAction.REVIEW,
            file("raw.ARW", 10, FileRecommendationRole.REVIEW),
            file("image.JPG", 20, FileRecommendationRole.REVIEW),
            file("video.MP4", 30, FileRecommendationRole.REVIEW),
            file("document.DOCX", 40, FileRecommendationRole.REVIEW),
            file("README", 50, FileRecommendationRole.REVIEW),
            file("unknown.xyz", 60, FileRecommendationRole.REVIEW),
        )), StandardTestDispatcher(testScheduler))
        val expected = mapOf(
            StatisticsFileType.IMAGES to FileStatistics(2, 30),
            StatisticsFileType.VIDEOS to FileStatistics(1, 30),
            StatisticsFileType.DOCUMENTS to FileStatistics(1, 40),
            StatisticsFileType.OTHER to FileStatistics(2, 110),
        )
        assertEquals(expected, result.byFileType)
        assertEquals(expected, result.byAction.getValue(RecommendationAction.REVIEW).byFileType)
        assertEquals(expected, result.byRole.getValue(FileRecommendationRole.REVIEW).byFileType)
    }

    @Test
    fun emptyInputIncludesAllZeroActionRoleAndTypeBuckets() = runTest {
        val result = aggregateRecommendationStatistics(emptyList(), StandardTestDispatcher(testScheduler))
        val zeroTypes = StatisticsFileType.entries.associateWith { FileStatistics() }
        assertEquals(0L, result.groupCount)
        assertEquals(FileStatistics(), result.totals)
        assertEquals(zeroTypes, result.byFileType)
        assertEquals(RecommendationAction.entries.toSet(), result.byAction.keys)
        assertEquals(FileRecommendationRole.entries.toSet(), result.byRole.keys)
        assertEquals(RecommendationAction.entries.associateWith { 0L }, result.groupCounts)
        (result.byAction.values + result.byRole.values).forEach {
            assertEquals(FileStatistics(), it.totals)
            assertEquals(zeroTypes, it.byFileType)
        }
    }

    @Test
    fun overlappingCohortsAreIndependentCalculations() = runTest {
        val shared = file("shared.jpg", 100, FileRecommendationRole.REVIEW)
        val regular = aggregateRecommendationStatistics(
            listOf(group(RecommendationAction.REVIEW, shared)), StandardTestDispatcher(testScheduler))
        val suspected = aggregateRecommendationStatistics(
            listOf(group(RecommendationAction.KEEP_MULTIPLE, shared,
                file("suspected.jpg", 200, FileRecommendationRole.PRESERVE))),
            StandardTestDispatcher(testScheduler))
        assertEquals(FileStatistics(1, 100), regular.totals)
        assertEquals(FileStatistics(2, 300), suspected.totals)
        assertEquals(FileStatistics(), regular.byAction.getValue(RecommendationAction.KEEP_MULTIPLE).totals)
    }

    @Test
    fun repeatedPathsAcrossGroupsAreRejected() = runTest {
        val shared = file("shared.jpg", 100, FileRecommendationRole.REVIEW)
        assertFailsWith<IllegalArgumentException> {
            aggregateRecommendationStatistics(listOf(
                group(RecommendationAction.REVIEW, shared),
                group(RecommendationAction.KEEP_MULTIPLE, shared),
            ), StandardTestDispatcher(testScheduler))
        }
    }

    @Test
    fun negativeFileSizesAreRejected() = runTest {
        assertFailsWith<IllegalArgumentException> {
            aggregateRecommendationStatistics(listOf(group(RecommendationAction.REVIEW,
                file("bad.jpg", -1, FileRecommendationRole.REVIEW))),
                StandardTestDispatcher(testScheduler))
        }
    }

    @Test
    fun longSizesRemainExactAndOverflowIsRejected() = runTest {
        val maximum = group(RecommendationAction.KEEP_ONE,
            file("large.jpg", Long.MAX_VALUE, FileRecommendationRole.PREFERRED),
            file("empty.jpg", 0, FileRecommendationRole.REDUNDANT_CANDIDATE))
        val result = aggregateRecommendationStatistics(listOf(maximum), StandardTestDispatcher(testScheduler))
        assertEquals(FileStatistics(2, Long.MAX_VALUE), result.totals)
        assertEquals(FileStatistics(2, Long.MAX_VALUE), result.byFileType.getValue(StatisticsFileType.IMAGES))
        assertFailsWith<ArithmeticException> {
            aggregateRecommendationStatistics(listOf(maximum,
                group(RecommendationAction.REVIEW, file("overflow.pdf", 1, FileRecommendationRole.REVIEW))),
                StandardTestDispatcher(testScheduler))
        }
    }

    @Test
    fun calculationIsDispatchedAndCancelledBeforePublishing() = runTest {
        var published = false
        val calculation = launch(start = CoroutineStart.UNDISPATCHED) {
            aggregateRecommendationStatistics(emptyList(), StandardTestDispatcher(testScheduler))
            published = true
        }
        assertFalse(published)
        calculation.cancel()
        runCurrent()
        calculation.join()
        assertFalse(published)
        assertTrue(calculation.isCancelled)
    }

    @Test
    fun cancellationIsCheckedDuringIteration() = runTest {
        lateinit var calculation: Job
        var published = false
        val groups = object : AbstractList<DuplicateGroupRecommendation>() {
            override val size = 2
            override fun get(index: Int): DuplicateGroupRecommendation {
                if (index == 1) calculation.cancel()
                return group(RecommendationAction.REVIEW,
                    file("$index.jpg", 10, FileRecommendationRole.REVIEW))
            }
        }
        calculation = launch {
            aggregateRecommendationStatistics(groups, StandardTestDispatcher(testScheduler))
            published = true
        }
        runCurrent()
        calculation.join()
        assertTrue(calculation.isCancelled)
        assertFalse(published)
    }
}
