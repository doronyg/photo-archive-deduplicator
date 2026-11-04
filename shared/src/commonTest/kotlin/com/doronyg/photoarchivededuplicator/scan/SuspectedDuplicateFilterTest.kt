package com.doronyg.photoarchivededuplicator.scan

import com.doronyg.photoarchivededuplicator.recommendation.DuplicateGroupRecommendation
import com.doronyg.photoarchivededuplicator.recommendation.EvidenceStrength
import com.doronyg.photoarchivededuplicator.recommendation.RecommendationAction
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

class SuspectedDuplicateFilterTest {
    private fun group(vararg paths: String): SuspectedDuplicateGroup =
        SuspectedDuplicateGroup(120_000, "2023:02:18 12:44:25", null, paths.map {
            val path = it.toPath()
            FileRecord(path, path.parent!!, path.name, "jpg", 120_000, null, 1)
        })

    @Test
    fun folderAndFilenameMustMatchTheSameMemberButAllGroupMembersAreRetained() = runTest {
        val source = group("/Photos/2023-02-18/IMG_1234.jpg", "/Backup/renamed.jpg")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val filter = SuspectedDuplicateFilter("/Photos".toPath(), "img_12??.JPG")
        val result = filterSuspectedDuplicateCandidates(listOf(source), filter, dispatcher = dispatcher)
        assertEquals(listOf(source), result)
        assertSame(source, result.single())
        assertSame(source.files, result.single().files)
        assertEquals(emptyList(), filterSuspectedDuplicateCandidates(listOf(source),
            filter.copy(filenamePattern = "renamed*"), dispatcher = dispatcher),
            "A filename in another folder must not satisfy the folder AND filename condition")
    }

    @Test
    fun foldersUseLiteralBoundariesAndCaseSensitivePathsNotSqlWildcards() = runTest {
        val wanted = group("/Photos/a%_/sub/photo.jpg", "/Elsewhere/photo-copy.jpg")
        val sibling = group("/Photos/a%_other/photo.jpg", "/Elsewhere/sibling.jpg")
        val wrongCase = group("/Photos/A%_/photo.jpg", "/Elsewhere/case.jpg")
        val dispatcher = StandardTestDispatcher(testScheduler)
        assertEquals(listOf(wanted), filterSuspectedDuplicateCandidates(listOf(sibling, wrongCase, wanted),
            SuspectedDuplicateFilter("/Photos/a%_".toPath()), dispatcher = dispatcher))
        assertFailsWith<IllegalArgumentException> { SuspectedDuplicateFilter("relative/path".toPath()) }
    }

    @Test
    fun wildcardReusesRegularMatchingRulesAndDefaultFiltersPreserveSearchOrder() = runTest {
        val wanted = group("/Photos/IMG_1234.jpg", "/Backup/other.jpg")
        val other = group("/Photos/IMG_1234Xjpg", "/Backup/another.jpg")
        val input = listOf(other, wanted)
        val dispatcher = StandardTestDispatcher(testScheduler)
        assertEquals(input, filterSuspectedDuplicateCandidates(input, SuspectedDuplicateFilter(), dispatcher = dispatcher))
        assertEquals(listOf(wanted), filterSuspectedDuplicateCandidates(input,
            SuspectedDuplicateFilter(filenamePattern = " img_????.JPG "), dispatcher = dispatcher))
        assertEquals(emptyList(), filterSuspectedDuplicateCandidates(emptyList(), SuspectedDuplicateFilter(), dispatcher = dispatcher))
    }

    @Test
    fun recommendationFilterCombinesWithMemberFiltersAndDoesNotInventMissingRecommendations() = runTest {
        val keep = group("/Photos/keep.jpg", "/Backup/keep-copy.jpg")
        val review = group("/Photos/review.jpg", "/Backup/review-copy.jpg")
        val pending = group("/Photos/pending.jpg", "/Backup/pending-copy.jpg")
        val recommendations = listOf(keep to RecommendationAction.KEEP_ONE, review to RecommendationAction.REVIEW)
            .flatMap { (group, action) ->
                group.files.map { it.path to DuplicateGroupRecommendation(action, EvidenceStrength.SUPPORTED,
                    emptySet(), "Fixture", emptyMap()) }
            }.toMap()
        val dispatcher = StandardTestDispatcher(testScheduler)
        assertEquals(listOf(keep), filterSuspectedDuplicateCandidates(listOf(pending, review, keep),
            SuspectedDuplicateFilter("/Photos".toPath(), "*.jpg", RecommendationAction.KEEP_ONE), recommendations, dispatcher))
        assertEquals(listOf(review), filterSuspectedDuplicateCandidates(listOf(pending, review, keep),
            SuspectedDuplicateFilter(recommendation = RecommendationAction.REVIEW), recommendations, dispatcher))
        assertEquals(emptyList(), filterSuspectedDuplicateCandidates(listOf(keep),
            SuspectedDuplicateFilter(filenamePattern = "no-match*", recommendation = RecommendationAction.KEEP_ONE),
            recommendations, dispatcher))
    }

    @Test
    fun filteringRunsOffCallerAndCancellationPreventsPublication() = runTest {
        var published: List<SuspectedDuplicateGroup>? = null
        val work = async(start = CoroutineStart.UNDISPATCHED) {
            published = filterSuspectedDuplicateCandidates(listOf(group("/Photos/a.jpg", "/Photos/b.jpg")),
                SuspectedDuplicateFilter(), dispatcher = StandardTestDispatcher(testScheduler))
        }
        assertFalse(work.isCompleted)
        work.cancel()
        runCurrent()
        work.join()
        assertEquals(null, published)
    }
}
