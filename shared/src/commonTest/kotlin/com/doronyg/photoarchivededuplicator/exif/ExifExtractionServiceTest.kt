package com.doronyg.photoarchivededuplicator.exif

import com.doronyg.photoarchivededuplicator.scan.DirectoryListing
import com.doronyg.photoarchivededuplicator.scan.concurrencyTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ExifExtractionServiceTest {
    @Test
    fun everyPersistedOutcomeIsReusedWithoutReopeningUnchangedFiles() = concurrencyTest { fixture ->
        for (status in ExifStatus.entries) {
            for (folder in listOf("a", "b")) {
                val directory = fixture.root / status.name / folder
                fixture.repository.seedRoot(directory)
                fixture.repository.recordListing(directory, DirectoryListing(emptyList(), listOf(
                    DirectoryListing.Entry(directory / "${status.name}.jpg", 12, null),
                )), 1)
            }
            val group = fixture.repository.duplicateCandidates(fixture.root).single { it.name == "${status.name}.jpg" }
            var reads = 0
            var time = 0L
            val service = ExifExtractionService(fixture.repository, ExifSource {
                reads++
                ExifReadResult(status)
            }, dispatcher = fixture.dispatcher, clock = { ++time })
            val first = service.extract(group)
            assertEquals(first, service.extract(group), "Cached $status must retain its original extraction time")
            assertEquals(2, reads, "Cached $status must not reopen either source")
        }
    }

    @Test
    fun storedResultsAndRepeatedRequestsNeverReadUnchangedMembersIncludingFailures() = concurrencyTest { fixture ->
        for (folder in listOf("a", "b", "c")) {
            val directory = fixture.root / folder
            fixture.repository.seedRoot(directory)
            fixture.repository.recordListing(directory, DirectoryListing(emptyList(), listOf(
                DirectoryListing.Entry(directory / "photo.jpg", 12, null),
            )), 1)
        }
        val group = fixture.repository.duplicateCandidates(fixture.root).single()
        val readPaths = mutableListOf<String>()
        val service = ExifExtractionService(fixture.repository, ExifSource { file ->
            readPaths += file.path.toString()
            when (file.directoryPath.name) {
                "a" -> ExifReadResult(ExifStatus.EXTRACTED, ExifMetadata(capturedAt = "2015:07:23 12:00:00"))
                "b" -> ExifReadResult(ExifStatus.ERROR, error = "Permission denied")
                else -> ExifReadResult(ExifStatus.NO_EXIF)
            }
        }, dispatcher = fixture.dispatcher, clock = { 42 })
        assertTrue(service.stored(listOf(group)).isEmpty())
        assertTrue(readPaths.isEmpty())
        val progress = mutableListOf<Int>()
        val first = service.extract(group) { completed, total ->
            assertEquals(3, total)
            progress += completed
        }
        assertEquals(listOf(0, 1, 2, 3), progress)
        assertEquals(setOf(ExifStatus.EXTRACTED, ExifStatus.ERROR, ExifStatus.NO_EXIF),
            first.values.map { it.result.status }.toSet())
        assertEquals(first, service.stored(listOf(group)))
        assertEquals(3, readPaths.size)
        service.extract(group)
        assertEquals(3, readPaths.size)
        for (file in group.files) {
            fixture.repository.recordListing(file.directoryPath, DirectoryListing(emptyList(), listOf(
                DirectoryListing.Entry(file.path, file.sizeBytes, file.modifiedAt, file.createdAt),
            )), 999)
        }
        val rescanned = fixture.repository.duplicateCandidates(fixture.root).single()
        assertEquals(first, service.extract(rescanned))
        assertEquals(3, readPaths.size, "A new lastScanned value alone must not reopen any file")
        assertTrue(fixture.repository.fileScores(group.files.map { it.path }).isEmpty())
    }

    @Test
    fun cancellationPreservesCompletedMembersWithoutSavingAnInterruptedFailure() = concurrencyTest { fixture ->
        for (folder in listOf("a", "b")) {
            val directory = fixture.root / folder
            fixture.repository.seedRoot(directory)
            fixture.repository.recordListing(directory, DirectoryListing(emptyList(), listOf(
                DirectoryListing.Entry(directory / "photo.jpg", 12, null),
            )), 1)
        }
        val group = fixture.repository.duplicateCandidates(fixture.root).single()
        val readingSecond = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        var reads = 0
        val service = ExifExtractionService(fixture.repository, ExifSource {
            if (++reads == 2) {
                readingSecond.complete(Unit)
                hold.await()
            }
            ExifReadResult(ExifStatus.NO_EXIF)
        }, dispatcher = fixture.dispatcher, clock = { 42 })
        val job = async { service.extract(group) }
        readingSecond.await()
        job.cancelAndJoin()
        assertEquals(1, service.stored(listOf(group)).size)
        assertTrue(service.stored(listOf(group)).values.all { it.result.status == ExifStatus.NO_EXIF })
    }

    @Test
    fun inventoryChangesPreventOldSnapshotsFromReadingOrPublishingMetadata() = concurrencyTest { fixture ->
        val directory = fixture.root / "a"
        fixture.repository.seedRoot(directory)
        fixture.repository.recordListing(directory, DirectoryListing(emptyList(), listOf(
            DirectoryListing.Entry(directory / "photo.jpg", 12, null),
        )), 1)
        val other = fixture.root / "b"
        fixture.repository.seedRoot(other)
        fixture.repository.recordListing(other, DirectoryListing(emptyList(), listOf(
            DirectoryListing.Entry(other / "photo.jpg", 12, null),
        )), 1)
        val oldGroup = fixture.repository.duplicateCandidates(fixture.root).single()
        var reads = 0
        val service = ExifExtractionService(fixture.repository, ExifSource {
            reads++
            ExifReadResult(ExifStatus.NO_EXIF)
        }, dispatcher = fixture.dispatcher, clock = { 42 })
        service.extract(oldGroup)
        fixture.repository.recordListing(directory, DirectoryListing(emptyList(), listOf(
            DirectoryListing.Entry(directory / "photo.jpg", 13, 999),
        )), 2)
        val oldStored = service.stored(listOf(oldGroup))
        assertEquals(setOf(other / "photo.jpg"), oldStored.keys)
        val results = service.extract(oldGroup)
        assertEquals(ExifStatus.STALE, results.getValue(directory / "photo.jpg").result.status)
        assertEquals(2, reads, "Changed snapshots must be rejected; unchanged members must reuse cached EXIF")
    }

    @Test
    fun changedInventoryAndLibraryVersionInvalidateOnlyTheirCachedInputs() = concurrencyTest { fixture ->
        for (folder in listOf("a", "b")) {
            val directory = fixture.root / folder
            fixture.repository.seedRoot(directory)
            fixture.repository.recordListing(directory, DirectoryListing(emptyList(), listOf(
                DirectoryListing.Entry(directory / "photo.jpg", 12, null),
            )), 1)
        }
        val groups = fixture.repository.duplicateCandidates(fixture.root)
        val group = groups.single()
        var reads = 0
        val reader = ExifSource { reads++; ExifReadResult(ExifStatus.LIMIT_EXCEEDED, error = "Read limit exceeded") }
        val service = ExifExtractionService(fixture.repository, reader, dispatcher = fixture.dispatcher, clock = { 42 })
        service.extract(group)
        service.extract(group)
        assertEquals(2, reads)
        val changed = group.files.first()
        fixture.repository.recordListing(changed.directoryPath, DirectoryListing(emptyList(), listOf(
            DirectoryListing.Entry(changed.path, 12, 999),
        )), 2)
        val refreshed = fixture.repository.duplicateCandidates(fixture.root).single()
        service.extract(refreshed)
        assertEquals(3, reads, "Only the modified file should be read again")
        val upgraded = ExifExtractionService(fixture.repository, reader, dispatcher = fixture.dispatcher,
            clock = { 43 }, readerVersion = "next-kim-version")
        val updated = upgraded.extract(refreshed)
        assertEquals(5, reads, "A library upgrade invalidates both prior extraction attempts")
        assertTrue(updated.values.all { it.readerVersion == "next-kim-version" })
        assertEquals(updated, upgraded.extract(refreshed))
        assertEquals(5, reads)
    }

    @Test
    fun inventoryChangeDuringReadRejectsTheCompletedOldInput() = concurrencyTest { fixture ->
        for (folder in listOf("a", "b")) {
            val directory = fixture.root / folder
            fixture.repository.seedRoot(directory)
            fixture.repository.recordListing(directory, DirectoryListing(emptyList(), listOf(
                DirectoryListing.Entry(directory / "photo.jpg", 12, null),
            )), 1)
        }
        val group = fixture.repository.duplicateCandidates(fixture.root).single()
        val reading = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = group.files.first()
        val service = ExifExtractionService(fixture.repository, ExifSource { file ->
            if (file.path == first.path) {
                reading.complete(Unit)
                release.await()
            }
            ExifReadResult(ExifStatus.EXTRACTED, ExifMetadata(make = "Example"))
        }, dispatcher = fixture.dispatcher, clock = { 42 })
        val job = async { service.extract(group) }
        reading.await()
        fixture.repository.recordListing(first.directoryPath, DirectoryListing(emptyList(), listOf(
            DirectoryListing.Entry(first.path, 13, 999),
        )), 2)
        release.complete(Unit)
        val result = job.await()
        assertEquals(ExifStatus.STALE, result.getValue(first.path).result.status)
        assertTrue(fixture.repository.fileExif(listOf(first.path), currentOnly = false).isEmpty())
        assertEquals(1, service.stored(listOf(group)).size)
    }

    @Test
    fun databaseFailurePropagatesWithoutReportingSuccessfulCompletion() = concurrencyTest { fixture ->
        for (folder in listOf("a", "b")) {
            val directory = fixture.root / folder
            fixture.repository.seedRoot(directory)
            fixture.repository.recordListing(directory, DirectoryListing(emptyList(), listOf(
                DirectoryListing.Entry(directory / "photo.jpg", 12, null),
            )), 1)
        }
        val group = fixture.repository.duplicateCandidates(fixture.root).single()
        val service = ExifExtractionService(fixture.repository,
            ExifSource { ExifReadResult(ExifStatus.NO_EXIF) },
            dispatcher = fixture.dispatcher, clock = { 42 })
        val progress = mutableListOf<Int>()
        fixture.driver.beforeStatement = { sql ->
            if (sql.contains("INTO fileExif", ignoreCase = true)) error("Database unavailable")
        }
        try {
            assertFailsWith<IllegalStateException> {
                service.extract(group) { completed, _ -> progress += completed }
            }
        } finally {
            fixture.driver.beforeStatement = {}
        }
        assertEquals(listOf(0), progress)
        assertTrue(service.stored(listOf(group)).isEmpty())
    }
}
