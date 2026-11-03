package com.doronyg.photoarchivededuplicator.exif

import com.doronyg.photoarchivededuplicator.retention.*
import com.doronyg.photoarchivededuplicator.scan.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class FileExifPersistenceTest {
    private suspend fun seed(f: ConcurrencyFixture): ExifRecord {
        f.repository.seedRoot(f.root)
        f.repository.recordListing(f.root, DirectoryListing(emptyList(), listOf(
            DirectoryListing.Entry(f.root / "photo.jpg", 42, 123, 100),
        )), 200)
        return ExifRecord(ExifInput.from(f.repository.file(f.root / "photo.jpg")!!),
            ExifReadResult(ExifStatus.EXTRACTED, ExifMetadata(
                "2024:02:29 12:34:56", "+02:00", "001", "雪\nmaker", "Camera", 6, 4032, 3024)), 300)
    }

    @Test
    fun sparseRowsRoundTripEveryStatusAndReplaceOnlyLatestExifWithoutChangingInventoryOrScores() = concurrencyTest { f ->
        val original = seed(f)
        val inventory = f.repository.allFiles()
        val score = FileScore(FileScoreInput.from(inventory.single()),
            ScoreIdentity("retention", "rules", "1", "default"), 0.5,
            listOf(ScoreFactor("metadata", 0.5, "Preserved")), 250)
        assertTrue(f.repository.saveFileScore(score))
        assertTrue(f.repository.fileExif(listOf(original.input.path)).isEmpty())
        assertTrue(f.repository.fileExif(emptyList()).isEmpty())
        for (status in ExifStatus.entries) {
            val record = original.copy(result = ExifReadResult(status, original.result.metadata, "Error\n雪"),
                extractedAt = 400, readerId = "other", readerVersion = "2")
            assertTrue(f.repository.saveFileExif(record))
            assertEquals(listOf(record), f.repository.fileExif(listOf(record.input.path, record.input.path)))
        }
        val empty = original.copy(result = ExifReadResult(ExifStatus.NO_EXIF))
        assertTrue(f.repository.saveFileExif(empty))
        assertEquals(listOf(empty), f.repository.fileExif(listOf(empty.input.path)))
        assertEquals(inventory, f.repository.allFiles())
        assertEquals(listOf(score), f.repository.fileScores(listOf(original.input.path)))
    }

    @Test
    fun writesRejectEveryChangedOrDeletedInputAndReadsHideStaleRowsByDefault() = concurrencyTest { f ->
        val original = seed(f)
        val input = original.input
        val paths = listOf(input.path)
        assertTrue(f.repository.saveFileExif(original))
        for (stale in listOf(
            input.copy(directoryPath = f.root / "other"), input.copy(sizeBytes = 43),
            input.copy(modifiedAt = null), input.copy(createdAt = null),
            input.copy(path = f.root / "missing"),
        )) {
            assertFalse(f.repository.saveFileExif(original.copy(input = stale)))
            assertEquals(listOf(original), f.repository.fileExif(paths))
        }
        f.repository.recordListing(f.root, DirectoryListing(emptyList(), listOf(
            DirectoryListing.Entry(input.path, 42, 123, 100),
        )), 999)
        assertEquals(listOf(original), f.repository.fileExif(paths))
        assertTrue(f.repository.saveFileExif(original))
        for (entry in listOf(
            DirectoryListing.Entry(input.path, 43, 123, 100),
            DirectoryListing.Entry(input.path, 42, null, 100),
            DirectoryListing.Entry(input.path, 42, 123, null),
        )) {
            f.repository.recordListing(f.root, DirectoryListing(emptyList(), listOf(entry)), 1000)
            assertTrue(f.repository.fileExif(paths).isEmpty())
            assertFalse(f.repository.saveFileExif(original))
            assertEquals(listOf(original), f.repository.fileExif(paths, currentOnly = false))
        }
        f.repository.recordListing(f.root, DirectoryListing(emptyList(), emptyList()), 1001)
        assertTrue(f.repository.fileExif(paths).isEmpty())
        assertFalse(f.repository.saveFileExif(original))
        assertEquals(listOf(original), f.repository.fileExif(paths, currentOnly = false))
    }

    @Test
    fun nullTimestampsAreCurrentAndLargeReadsUseBoundedQueries() = concurrencyTest { f ->
        f.repository.seedRoot(f.root)
        val entries = (0 until 1100).map { DirectoryListing.Entry(f.root / "$it.jpg", it.toLong(), null) }
        f.repository.recordListing(f.root, DirectoryListing(emptyList(), entries), 200)
        val records = f.repository.allFiles().map {
            ExifRecord(ExifInput.from(it), ExifReadResult(ExifStatus.NO_EXIF), 300)
        }
        records.forEach { assertTrue(f.repository.saveFileExif(it)) }
        for (currentOnly in listOf(true, false)) {
            val start = f.driver.statements.size
            assertEquals(records.toSet(), f.repository.fileExif(records.map { it.input.path }, currentOnly).toSet())
            val reads = f.driver.statements.drop(start)
            assertEquals(3, reads.size)
            assertTrue(reads.all { it.startsWith("SELECT") })
        }
    }

    @Test
    fun cancelledMutationRollsBackBeforeQueuedRead() = concurrencyTest { f ->
        val original = seed(f)
        assertTrue(f.repository.saveFileExif(original))
        f.driver.pauseAfter = { it.startsWith("INSERT OR REPLACE INTO fileExif") }
        val save = launch { f.repository.saveFileExif(original.copy(extractedAt = 999)) }
        f.driver.entered.await()
        val read = async { f.repository.fileExif(listOf(original.input.path)) }
        runCurrent()
        assertFalse(read.isCompleted)
        save.cancelAndJoin()
        assertEquals(listOf(original), read.await())
        assertNull(f.driver.currentTransaction())
    }

    @Test
    fun failedWriteLeavesPreviousResultAndReleasesTransaction() = concurrencyTest { f ->
        val original = seed(f)
        assertTrue(f.repository.saveFileExif(original))
        f.driver.beforeStatement = {
            if (it.startsWith("INSERT OR REPLACE INTO fileExif")) error("EXIF write failed")
        }
        assertFailsWith<IllegalStateException> {
            f.repository.saveFileExif(original.copy(extractedAt = 999))
        }
        f.driver.beforeStatement = {}
        assertNull(f.driver.currentTransaction())
        assertEquals(listOf(original), f.repository.fileExif(listOf(original.input.path)))
        assertTrue(f.repository.saveFileExif(original.copy(extractedAt = 1000)))
    }
}
