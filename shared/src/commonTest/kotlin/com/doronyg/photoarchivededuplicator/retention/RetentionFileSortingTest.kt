package com.doronyg.photoarchivededuplicator.retention

import com.doronyg.photoarchivededuplicator.scan.DuplicateCandidateGroup
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import com.doronyg.photoarchivededuplicator.scan.TrackingIoDispatcher
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
class RetentionFileSortingTest {
    private val scorer = RetentionScorer()
    private fun file(folder: String): FileRecord {
        val directory = "/archive/$folder".toPath()
        return FileRecord(directory / "photo.jpg", directory, "photo.jpg", "jpg", 12, null, 1)
    }

    private fun score(file: FileRecord, value: Double, rank: RetentionRank = RetentionRank.LOWER) =
        RetentionPresentation(scorer.score(file, 1).copy(value = value), rank)

    @Test
    fun membersSortDescendingIncludingNegativeScoresWithoutChangingGroupOrderOrInputs() = runTest(timeout = 10.seconds) {
        val low = file("low")
        val high = file("high")
        val middle = file("middle")
        val first = DuplicateCandidateGroup("photo.jpg", 12, listOf(low, high, middle))
        val second = DuplicateCandidateGroup("other.jpg", 20, emptyList())
        val scores = mapOf(low.path to score(low, -60.0), high.path to score(high, 100.0),
            middle.path to score(middle, -25.0))
        val result = sortDuplicateFilesByRetention(listOf(first, second), scores, StandardTestDispatcher(testScheduler))
        assertEquals(listOf(high, middle, low), result.first().files)
        assertEquals(listOf("photo.jpg", "other.jpg"), result.map { it.name })
        assertEquals(listOf(low, high, middle), first.files)
        assertEquals(12L, result.first().sizeBytes)
        assertEquals(second, result[1])
    }

    @Test
    fun tiesUsePathOrderAndCloseScoresKeepTheirAmbiguousLabels() = runTest(timeout = 10.seconds) {
        val z = file("z")
        val a = file("a")
        val b = file("b")
        val group = DuplicateCandidateGroup("photo.jpg", 12, listOf(z, a, b))
        val scores = mapOf(z.path to score(z, 100.0, RetentionRank.CLOSE),
            a.path to score(a, 100.0, RetentionRank.CLOSE), b.path to score(b, 103.0, RetentionRank.CLOSE))
        val result = sortDuplicateFilesByRetention(listOf(group), scores, StandardTestDispatcher(testScheduler))
        assertEquals(listOf(b, a, z), result.single().files)
        assertEquals(setOf(RetentionRank.CLOSE), scores.values.map { it.rank }.toSet())
    }

    @Test
    fun missingAndStaleScoresFollowAllCurrentScoresRatherThanBeingTreatedAsZero() = runTest(timeout = 10.seconds) {
        val missing = file("a-missing")
        val stale = file("b-stale")
        val mismatched = file("c-mismatched")
        val negative = file("z-negative")
        val group = DuplicateCandidateGroup("photo.jpg", 12, listOf(missing, stale, mismatched, negative))
        val scores = mapOf(
            stale.path to score(stale, 999.0, RetentionRank.STALE),
            mismatched.path to score(mismatched, 999.0).let {
                it.copy(score = it.score!!.copy(input = it.score.input.copy(modifiedAt = 42)))
            },
            negative.path to score(negative, -60.0, RetentionRank.INCOMPLETE),
        )
        val result = sortDuplicateFilesByRetention(listOf(group), scores, StandardTestDispatcher(testScheduler))
        assertEquals(listOf(negative, missing, stale, mismatched), result.single().files)
    }

    @Test
    fun memberSortingRunsOffCallerAndHandlesEmptyGroups() = runTest(timeout = 10.seconds) {
        val worker = TrackingIoDispatcher(StandardTestDispatcher(testScheduler))
        val files = object : AbstractList<FileRecord>() {
            override val size = 2
            override fun get(index: Int): FileRecord {
                worker.assertOnIo("sort duplicate group members")
                return file(if (index == 0) "z" else "a")
            }
        }
        val group = DuplicateCandidateGroup("photo.jpg", 12, files)
        val result = sortDuplicateFilesByRetention(listOf(group), emptyMap(), worker)
        assertEquals(listOf(file("a"), file("z")), result.single().files)
        assertEquals(emptyList(), sortDuplicateFilesByRetention(emptyList(), emptyMap(), worker))
    }

    @Test
    fun cancelledSortCannotPublishObsoleteMemberOrder() = runTest(timeout = 10.seconds) {
        var published: List<DuplicateCandidateGroup>? = null
        val sorting = async(start = CoroutineStart.UNDISPATCHED) {
            published = sortDuplicateFilesByRetention(
                listOf(DuplicateCandidateGroup("photo.jpg", 12, listOf(file("z"), file("a")))),
                emptyMap(), StandardTestDispatcher(testScheduler),
            )
        }
        assertFalse(sorting.isCompleted)
        sorting.cancel()
        runCurrent()
        sorting.join()
        assertEquals(null, published)
    }
}
