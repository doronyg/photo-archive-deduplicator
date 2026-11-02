package com.doronyg.photoarchivededuplicator.retention

import com.doronyg.photoarchivededuplicator.scan.DirectoryListing
import com.doronyg.photoarchivededuplicator.scan.DirectoryRepository
import com.doronyg.photoarchivededuplicator.scan.DuplicateCandidateGroup
import com.doronyg.photoarchivededuplicator.scan.TrackingIoDispatcher
import com.doronyg.photoarchivededuplicator.scan.concurrencyTest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import okio.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RetentionScoreServiceTest {
    private suspend fun record(repository: DirectoryRepository, directory: Path, modified: Long? = null, scanned: Long = 1) {
        repository.seedRoot(directory)
        repository.recordListing(directory, DirectoryListing(emptyList(), listOf(
            DirectoryListing.Entry(directory / "photo.jpg", 12, modified),
        )), scanned)
    }

    @Test
    fun scoringAndRankingReadCandidateMetadataOffCallerDispatcher() = concurrencyTest { fixture ->
        record(fixture.repository, fixture.root / "2007-06-06")
        record(fixture.repository, fixture.root / "Google Photos")
        val stored = fixture.repository.duplicateCandidates(fixture.root)
        val worker = TrackingIoDispatcher(fixture.dispatcher)
        val guarded = object : AbstractList<DuplicateCandidateGroup>() {
            override val size = stored.size
            override fun get(index: Int): DuplicateCandidateGroup {
                worker.assertOnIo("retention candidate processing")
                return stored[index]
            }
        }
        val service = RetentionScoreService(fixture.repository, dispatcher = worker)
        assertEquals(2, service.assess(guarded).files.size)
    }

    @Test
    fun algorithmUpgradePreservesEarlierVersionsAndCachesCurrentAssessments() = concurrencyTest { fixture ->
        val filename = "IMG-20150723-WA0003.jpg"
        for (folder in listOf("2016-06-08", "2016-12-17-Whats Up", "PhotoSync/WhatsApp Images")) {
            val directory = fixture.root / folder
            fixture.repository.seedRoot(directory)
            fixture.repository.recordListing(directory, DirectoryListing(emptyList(), listOf(
                DirectoryListing.Entry(directory / filename, 12, null),
            )), 1)
        }
        val groups = fixture.repository.duplicateCandidates(fixture.root)
        val scorer = RetentionScorer()
        val legacyIdentities = listOf("1", "2").map {
            scorer.identity.copy(algorithmVersion = it, configurationId = "historical-v$it")
        }
        val oldScores = legacyIdentities.flatMap { identity ->
            groups.single().files.map { scorer.score(it, 1).copy(identity = identity, value = 102.0) }
        }
        fixture.repository.saveFileScores(oldScores)
        var computations = 0
        val service = RetentionScoreService(fixture.repository, dispatcher = fixture.dispatcher, clock = { (++computations).toLong() })
        val current = service.assess(groups)
        assertEquals(listOf(-25.0, 20.0, 20.0), current.files.values.map { it.score!!.value }.sorted())
        assertTrue(current.files.values.all { it.score!!.identity.algorithmVersion == "3" })
        assertEquals(2, current.files.values.count { it.rank == RetentionRank.TIED })
        for (identity in legacyIdentities) {
            assertEquals(oldScores.filter { it.identity == identity }.toSet(),
                fixture.repository.fileScores(groups.single().files.map { it.path }, identity).toSet())
        }
        service.assess(groups)
        assertEquals(3, computations, "Current assessments should be reused after the first calculation")
    }

    @Test
    fun cachesUnchangedInputsButExplicitRecomputeUpdatesAssessmentTime() = concurrencyTest { fixture ->
        val a = fixture.root / "2007-06-06"
        val b = fixture.root / "Google Photos"
        record(fixture.repository, a)
        record(fixture.repository, b)
        fixture.fs.onBlockingCall = { error("Scoring must never inspect the filesystem") }
        var clockCalls = 0L
        val service = RetentionScoreService(fixture.repository, dispatcher = fixture.dispatcher, clock = { ++clockCalls })
        val groups = fixture.repository.duplicateCandidates(fixture.root)
        val first = service.assess(groups)
        assertEquals(2L, clockCalls)
        assertEquals(setOf(RetentionRank.HIGHEST, RetentionRank.LOWER), first.files.values.map { it.rank }.toSet())
        assertEquals(first, service.assess(groups))
        assertEquals(2L, clockCalls, "Unchanged scores should be read, not recomputed")
        val forced = service.assess(groups, force = true)
        assertEquals(4L, clockCalls)
        assertEquals(first.files.mapValues { it.value.score!!.value }, forced.files.mapValues { it.value.score!!.value })
        assertTrue(forced.files.values.all { it.score!!.createdAt > 2 })
        record(fixture.repository, a, scanned = 999)
        service.assess(fixture.repository.duplicateCandidates(fixture.root))
        assertEquals(4L, clockCalls, "lastScanned alone is not a scoring input")
        record(fixture.repository, a, modified = 10)
        service.assess(fixture.repository.duplicateCandidates(fixture.root))
        assertEquals(5L, clockCalls, "Only the changed file should be recomputed")
    }

    @Test
    fun staleBrowserSnapshotCannotPublishFreshLookingScoresAfterInventoryChanges() = concurrencyTest { fixture ->
        val a = fixture.root / "2007-06-06"
        val b = fixture.root / "Google Photos"
        record(fixture.repository, a)
        record(fixture.repository, b)
        val service = RetentionScoreService(fixture.repository, dispatcher = fixture.dispatcher, clock = { 1 })
        val oldGroups = fixture.repository.duplicateCandidates(fixture.root)
        service.assess(oldGroups)
        record(fixture.repository, a, modified = 42)
        val result = service.assess(oldGroups, force = true)
        assertEquals(1, result.invalidatedFiles)
        assertEquals(RetentionRank.MISSING, result.files.getValue(a / "photo.jpg").rank)
        assertEquals(RetentionRank.INCOMPLETE, result.files.getValue(b / "photo.jpg").rank)
        val refreshed = service.assess(fixture.repository.duplicateCandidates(fixture.root))
        assertEquals(0, refreshed.invalidatedFiles)
        assertEquals(RetentionRank.HIGHEST, refreshed.files.getValue(a / "photo.jpg").rank)
    }

    @Test
    fun cacheHitsAreRevalidatedAfterConcurrentMetadataChangeOrDeletion() = concurrencyTest { fixture ->
        val a = fixture.root / "2007-06-06"
        val b = fixture.root / "Google Photos"
        record(fixture.repository, a)
        record(fixture.repository, b)
        val service = RetentionScoreService(fixture.repository, dispatcher = fixture.dispatcher, clock = { 1 })
        val groups = fixture.repository.duplicateCandidates(fixture.root)
        service.assess(groups)
        record(fixture.repository, a, modified = 42)
        val changed = service.assess(groups)
        assertEquals(1, changed.invalidatedFiles, "A cache hit must not bypass current inventory validation")
        assertEquals(RetentionRank.MISSING, changed.files.getValue(a / "photo.jpg").rank)
        assertEquals(RetentionRank.INCOMPLETE, changed.files.getValue(b / "photo.jpg").rank)
        val refreshed = fixture.repository.duplicateCandidates(fixture.root)
        service.assess(refreshed)
        fixture.repository.recordListing(a, DirectoryListing(emptyList(), emptyList()), 999)
        val deleted = service.assess(refreshed)
        assertEquals(1, deleted.invalidatedFiles)
        assertEquals(RetentionRank.MISSING, deleted.files.getValue(a / "photo.jpg").rank)
        assertEquals(RetentionRank.INCOMPLETE, deleted.files.getValue(b / "photo.jpg").rank)
    }

    @Test
    fun cancellationRollsBackCurrentSaveAndReleasesServiceForWaitingRequest() = concurrencyTest { fixture ->
        record(fixture.repository, fixture.root / "2007-06-06")
        record(fixture.repository, fixture.root / "Google Photos")
        var computations = 0
        val service = RetentionScoreService(fixture.repository, dispatcher = fixture.dispatcher, clock = { (++computations).toLong() })
        val groups = fixture.repository.duplicateCandidates(fixture.root)
        fixture.driver.pauseAfter = { it.startsWith("INSERT INTO fileScore") }
        val first = async { service.assess(groups) }
        fixture.driver.entered.await()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { service.assess(groups) }
        runCurrent()
        assertFalse(waiting.isCompleted)
        assertEquals(2, computations)
        first.cancelAndJoin()
        val result = waiting.await()
        assertEquals(2, result.files.size)
        assertTrue(result.files.values.all { it.score != null })
        assertEquals(2, fixture.repository.fileScores(groups.flatMap { it.files }.map { it.path }).size)
    }

    @Test
    fun databaseFailurePropagatesAndNextAssessmentCanProceed() = concurrencyTest { fixture ->
        record(fixture.repository, fixture.root / "2007-06-06")
        record(fixture.repository, fixture.root / "Google Photos")
        val service = RetentionScoreService(fixture.repository, dispatcher = fixture.dispatcher)
        val groups = fixture.repository.duplicateCandidates(fixture.root)
        var fail = true
        fixture.driver.beforeStatement = {
            if (fail && it.startsWith("INSERT INTO fileScore")) {
                fail = false
                error("injected score write failure")
            }
        }
        assertFailsWith<IllegalStateException> { service.assess(groups) }
        assertEquals(2, service.assess(groups).files.size)
    }
}
