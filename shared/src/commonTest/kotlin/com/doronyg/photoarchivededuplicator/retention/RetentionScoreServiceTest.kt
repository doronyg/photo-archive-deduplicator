package com.doronyg.photoarchivededuplicator.retention

import com.doronyg.photoarchivededuplicator.exif.ExifExtractionService
import com.doronyg.photoarchivededuplicator.exif.ExifInput
import com.doronyg.photoarchivededuplicator.exif.ExifMetadata
import com.doronyg.photoarchivededuplicator.exif.ExifReadResult
import com.doronyg.photoarchivededuplicator.exif.ExifRecord
import com.doronyg.photoarchivededuplicator.exif.ExifSource
import com.doronyg.photoarchivededuplicator.exif.ExifStatus
import com.doronyg.photoarchivededuplicator.scan.DirectoryListing
import com.doronyg.photoarchivededuplicator.scan.DirectoryRepository
import com.doronyg.photoarchivededuplicator.scan.DuplicateCandidateGroup
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import com.doronyg.photoarchivededuplicator.scan.TrackingIoDispatcher
import com.doronyg.photoarchivededuplicator.scan.concurrencyTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import okio.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class RetentionScoreServiceTest {
    private fun exif(file: FileRecord, date: String = "2013:06:25 12:00:00") =
        ExifRecord(ExifInput.from(file), ExifReadResult(ExifStatus.EXTRACTED, ExifMetadata(capturedAt = date)), 2)

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
        val legacyIdentities = listOf("1", "2", "3", "4").map {
            scorer.identity.copy(algorithmVersion = it, configurationId = "historical-v$it")
        } + scorer.identity.copy(type = "ai-quality", algorithmId = "historical-model")
        val oldScores = legacyIdentities.flatMap { identity ->
            groups.single().files.map { scorer.score(it, 1).copy(identity = identity, value = 102.0) }
        }
        fixture.repository.saveFileScores(oldScores)
        var computations = 0
        val service = RetentionScoreService(fixture.repository, dispatcher = fixture.dispatcher, clock = { (++computations).toLong() })
        val current = service.assess(groups)
        assertEquals(listOf(-25.0, 60.0, 60.0), current.files.values.map { it.score!!.value }.sorted())
        assertTrue(current.files.values.all { it.score!!.identity.algorithmVersion == "5" })
        assertEquals(2, current.files.values.count { it.rank == RetentionRank.TIED })
        for (identity in legacyIdentities) {
            assertEquals(oldScores.filter { it.identity == identity }.toSet(),
                fixture.repository.fileScores(groups.single().files.map { it.path }, identity).toSet())
        }
        service.assess(groups)
        assertEquals(3, computations, "Current assessments should be reused after the first calculation")
    }

    @Test
    fun storedExifChangesScoresRanksAndMemberOrderWithoutReadingFiles() = concurrencyTest { fixture ->
        record(fixture.repository, fixture.root / "2013_06_25")
        record(fixture.repository, fixture.root / "20130625")
        record(fixture.repository, fixture.root / "2013/06/25")
        val groups = fixture.repository.duplicateCandidates(fixture.root)
        val files = groups.single().files
        val matched = files.single { it.directoryPath.name == "2013_06_25" }
        val contradicted = files.single { it.directoryPath.name == "20130625" }
        val neutral = files.single { it.directoryPath.name == "25" }
        val service = RetentionScoreService(fixture.repository, dispatcher = fixture.dispatcher, clock = { 10 })
        val before = service.assess(groups)
        assertTrue(before.files.values.all { it.rank == RetentionRank.TIED })
        fixture.repository.saveFileExif(exif(matched))
        fixture.repository.saveFileExif(exif(contradicted, "2013:06:30 17:43:36"))
        fixture.fs.onBlockingCall = { error("Retention scoring must use stored EXIF, never source files") }
        val after = service.assess(groups)
        assertEquals(90.0, after.files.getValue(matched.path).score!!.value)
        assertEquals(0.0, after.files.getValue(contradicted.path).score!!.value)
        assertEquals(40.0, after.files.getValue(neutral.path).score!!.value)
        assertEquals(RetentionRank.HIGHEST, after.files.getValue(matched.path).rank)
        assertEquals(RetentionRank.LOWER, after.files.getValue(contradicted.path).rank)
        assertEquals(listOf(matched, neutral, contradicted),
            sortDuplicateFilesByRetention(groups, after.files, fixture.dispatcher).single().files)
    }

    @Test
    fun storedExifMismatchSuppressesMatchingInventoryTimestampSupport() = concurrencyTest { fixture ->
        val timestamp = Instant.parse("2013-06-25T12:00:00Z").toEpochMilliseconds()
        for (folder in listOf("2013_06_25", "2013-06-25")) {
            val directory = fixture.root / folder
            fixture.repository.seedRoot(directory)
            fixture.repository.recordListing(directory, DirectoryListing(emptyList(), listOf(
                DirectoryListing.Entry(directory / "photo.jpg", 12, timestamp, timestamp),
            )), 1)
        }
        val groups = fixture.repository.duplicateCandidates(fixture.root)
        groups.single().files.forEach {
            fixture.repository.saveFileExif(exif(it, "2013:06:30 17:43:36"))
        }
        val result = RetentionScoreService(fixture.repository, dispatcher = fixture.dispatcher).assess(groups)
        assertEquals(listOf(0.0, 60.0), result.files.values.map { it.score!!.value }.sorted())
        assertTrue(result.files.values.none { presentation ->
            presentation.score!!.factors.any { it.key.endsWith("-date-support") }
        })
    }

    @Test
    fun cacheTracksUsableOriginalDateNotExtractionTimeCameraOrTimeOfDay() = concurrencyTest { fixture ->
        record(fixture.repository, fixture.root / "2013_06_25")
        record(fixture.repository, fixture.root / "20130625")
        val groups = fixture.repository.duplicateCandidates(fixture.root)
        val file = groups.single().files.first()
        var computations = 0L
        val service = RetentionScoreService(fixture.repository, dispatcher = fixture.dispatcher, clock = { ++computations })
        service.assess(groups)
        assertEquals(2L, computations)
        val original = exif(file)
        fixture.repository.saveFileExif(original)
        assertEquals(90.0, service.assess(groups).files.getValue(file.path).score!!.value)
        assertEquals(3L, computations, "Adding usable EXIF invalidates a neutral cached score")
        fixture.repository.saveFileExif(original.copy(
            result = original.result.copy(metadata = original.result.metadata.copy(
                capturedAt = "2013:06:25 23:59:59", make = "Changed camera", offsetOriginal = "+14:00")),
            extractedAt = 999, readerVersion = "another-reader",
        ))
        service.assess(groups)
        assertEquals(3L, computations, "Only the original calendar date affects cache validity")
        fixture.repository.saveFileExif(exif(file, "2013:06:30 12:00:00"))
        assertEquals(0.0, service.assess(groups).files.getValue(file.path).score!!.value)
        assertEquals(4L, computations)
        fixture.repository.saveFileExif(original.copy(result = ExifReadResult(ExifStatus.ERROR, error = "Read failed")))
        assertEquals(40.0, service.assess(groups).files.getValue(file.path).score!!.value)
        assertEquals(5L, computations, "A failed refresh removes prior successful evidence")
        fixture.repository.saveFileExif(original.copy(result = ExifReadResult(ExifStatus.EXTRACTED,
            ExifMetadata(capturedAt = "1970:01:01 00:00:00"))))
        service.assess(groups)
        assertEquals(5L, computations, "Unusable EXIF is equivalent to absent evidence")
        fixture.repository.saveFileExif(original)
        service.assess(groups)
        assertEquals(6L, computations)
        fixture.lane.run("remove EXIF fixture") {
            fixture.driver.execute(null, "DELETE FROM fileExif WHERE filePath = ?", 1) {
                bindString(0, file.path.toString())
            }.await()
        }
        assertEquals(40.0, service.assess(groups).files.getValue(file.path).score!!.value)
        assertEquals(7L, computations, "Removing usable EXIF invalidates its cached evidence")
    }

    @Test
    fun cancelledExtractionReassessesCompletedMembersAndKeepsUntouchedScoresCached() = concurrencyTest { fixture ->
        record(fixture.repository, fixture.root / "2013_06_25")
        record(fixture.repository, fixture.root / "20130625")
        val groups = fixture.repository.duplicateCandidates(fixture.root)
        var computations = 0L
        val service = RetentionScoreService(fixture.repository, dispatcher = fixture.dispatcher, clock = { ++computations })
        service.assess(groups)
        val readingSecond = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        var reads = 0
        val extraction = ExifExtractionService(fixture.repository, ExifSource {
            if (++reads == 2) {
                readingSecond.complete(Unit)
                hold.await()
            }
            ExifReadResult(ExifStatus.EXTRACTED, ExifMetadata(capturedAt = "2013:06:25 12:00:00"))
        }, dispatcher = fixture.dispatcher)
        val job = async { extraction.extract(groups.single()) }
        readingSecond.await()
        job.cancelAndJoin()
        val result = service.assess(groups)
        assertEquals(listOf(40.0, 90.0), result.files.values.map { it.score!!.value }.sorted())
        assertEquals(3L, computations, "Completed EXIF must affect the next assessment after cancellation")
        assertEquals(2, reads, "Scoring must never request additional extraction")
    }

    @Test
    fun storedExifBecomesNeutralAfterItsInventorySnapshotChanges() = concurrencyTest { fixture ->
        val directory = fixture.root / "2013_06_25"
        record(fixture.repository, directory)
        record(fixture.repository, fixture.root / "20130625")
        val oldGroups = fixture.repository.duplicateCandidates(fixture.root)
        val file = oldGroups.single().files.single { it.directoryPath == directory }
        fixture.repository.saveFileExif(exif(file))
        val service = RetentionScoreService(fixture.repository, dispatcher = fixture.dispatcher)
        assertEquals(90.0, service.assess(oldGroups).files.getValue(file.path).score!!.value)
        record(fixture.repository, directory, modified = 42)
        val fresh = service.assess(fixture.repository.duplicateCandidates(fixture.root))
        assertEquals(40.0, fresh.files.getValue(file.path).score!!.value)
        assertTrue(fixture.repository.fileExif(listOf(file.path), currentOnly = true).isEmpty())
        assertEquals(1, fixture.repository.fileExif(listOf(file.path), currentOnly = false).size)
    }

    @Test
    fun extractionCompletionReassessmentWinsOverAnOverlappingForcedRecompute() = concurrencyTest { fixture ->
        record(fixture.repository, fixture.root / "2013_06_25")
        record(fixture.repository, fixture.root / "20130625")
        val groups = fixture.repository.duplicateCandidates(fixture.root)
        var computations = 0L
        val service = RetentionScoreService(fixture.repository, dispatcher = fixture.dispatcher, clock = { ++computations })
        service.assess(groups)
        val reading = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        var reads = 0
        val extraction = ExifExtractionService(fixture.repository, ExifSource {
            if (++reads == 1) {
                reading.complete(Unit)
                releaseRead.await()
            }
            ExifReadResult(ExifStatus.EXTRACTED, ExifMetadata(capturedAt = "2013:06:25 12:00:00"))
        }, dispatcher = fixture.dispatcher)
        val refreshed = async {
            extraction.extract(groups.single())
            service.assess(groups)
        }
        reading.await()
        fixture.driver.pauseAfter = { it.startsWith("INSERT INTO fileScore") }
        val recomputed = async { service.assess(groups, force = true) }
        fixture.driver.entered.await()
        releaseRead.complete(Unit)
        runCurrent()
        assertFalse(refreshed.isCompleted)
        fixture.driver.release.complete(Unit)
        assertTrue(recomputed.await().files.values.all { it.score!!.value == 40.0 })
        val result = refreshed.await()
        assertTrue(result.files.values.all { it.score!!.value == 90.0 })
        assertTrue(fixture.repository.fileScores(groups.single().files.map { it.path }, RetentionScorer().identity)
            .all { it.value == 90.0 })
        assertEquals(6L, computations)
        assertEquals(2, reads)
    }

    @Test
    fun exifChangedDuringCachedScoreQueryIsComparedAgainstLatestEvidence() = concurrencyTest { fixture ->
        record(fixture.repository, fixture.root / "2013_06_25")
        record(fixture.repository, fixture.root / "20130625")
        val groups = fixture.repository.duplicateCandidates(fixture.root)
        val file = groups.single().files.first()
        fixture.repository.saveFileExif(exif(file))
        var computations = 0L
        val service = RetentionScoreService(fixture.repository, dispatcher = fixture.dispatcher, clock = { ++computations })
        service.assess(groups)
        var update = true
        fixture.driver.beforeStatement = { sql ->
            if (update && "FROM fileScore" in sql && "FROM fileScoreFactor" !in sql) {
                update = false
                fixture.driver.execute(null, "UPDATE fileExif SET capturedAt = ? WHERE filePath = ?", 2) {
                    bindString(0, "2013:06:30 12:00:00")
                    bindString(1, file.path.toString())
                }
            }
        }
        val result = service.assess(groups)
        assertFalse(update, "The mutation must run during the cached score query")
        assertEquals(0.0, result.files.getValue(file.path).score!!.value)
        assertEquals(3L, computations)
    }

    @Test
    fun exifChangedBetweenSnapshotAndWriteRejectsTheComputedScoreWithoutDeletingHistory() = concurrencyTest { fixture ->
        record(fixture.repository, fixture.root / "2013_06_25")
        record(fixture.repository, fixture.root / "20130625")
        val groups = fixture.repository.duplicateCandidates(fixture.root)
        val file = groups.single().files.first()
        fixture.repository.saveFileExif(exif(file))
        val scorer = RetentionScorer()
        val historical = scorer.score(file, 1, exif(file)).copy(identity = scorer.identity.copy(algorithmVersion = "3"))
        fixture.repository.saveFileScore(historical)
        var inject = true
        var mutation: Job? = null
        val service = RetentionScoreService(fixture.repository, dispatcher = fixture.dispatcher, clock = {
            if (inject) {
                inject = false
                mutation = launch(start = CoroutineStart.UNDISPATCHED) {
                    fixture.repository.saveFileExif(exif(file, "2013:06:30 12:00:00"))
                }
            }
            10
        })
        val result = service.assess(groups)
        mutation!!.join()
        assertEquals(1, result.invalidatedFiles)
        assertEquals(RetentionRank.MISSING, result.files.getValue(file.path).rank)
        assertTrue(fixture.repository.fileScores(listOf(file.path), scorer.identity).isEmpty())
        assertEquals(listOf(historical), fixture.repository.fileScores(listOf(file.path), historical.identity))
        val refreshed = service.assess(groups)
        assertEquals(0, refreshed.invalidatedFiles)
        assertEquals(0.0, refreshed.files.getValue(file.path).score!!.value)
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
