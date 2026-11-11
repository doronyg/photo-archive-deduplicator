package com.doronyg.photoarchivededuplicator.ui

import com.doronyg.photoarchivededuplicator.classification.OllamaSettings
import com.doronyg.photoarchivededuplicator.retention.RetentionScoreService
import com.doronyg.photoarchivededuplicator.scan.DirectoryListing
import com.doronyg.photoarchivededuplicator.scan.concurrencyTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CleanupResultsServiceTest {
    @Test
    fun readsDatabaseOnlyKeepsExcludedInventoryAndDoesNotSaveScoresOrExif() = concurrencyTest { f ->
        val first = f.root / "first"
        val second = f.root / "second"
        val excluded = f.root / "excluded"
        for (directory in listOf(first, second, excluded)) {
            f.repository.seedRoot(directory)
            f.repository.recordListing(directory, DirectoryListing(emptyList(), listOf(
                DirectoryListing.Entry(directory / "IMG.jpg", 120_000, 1, 2),
                DirectoryListing.Entry(directory / "IMG-001.jpg", 120_000, 1, 2),
            )), 3)
        }
        f.repository.addExclusion(excluded)
        val retention = RetentionScoreService(f.repository, dispatcher = f.dispatcher)
        val regular = f.repository.duplicateCandidates()
        val suspected = f.repository.suspectedDuplicates()
        val beforeRegular = retention.stored(regular)
        val beforeSuspected = retention.storedSuspected(suspected)
        val statements = f.driver.statements.size
        f.fs.onBlockingCall = { error("Cleanup Results must not inspect archive files") }
        val profile = OllamaSettings("http://ollama.test", "model").context()
        val snapshot = CleanupResultsService(f.repository, retention, f.dispatcher, profile).load()
        assertEquals(6L, snapshot.inventory.fileCount)
        assertEquals(720_000L, snapshot.inventory.totalBytes)
        assertEquals(4L, snapshot.analysis.inventory.all.totalFiles)
        assertEquals(4L, snapshot.analysis.inventory.all.possibleDuplicateFiles)
        assertEquals(profile, snapshot.analysis.classificationContext)
        assertTrue(f.driver.statements.drop(statements).all { it.startsWith("SELECT") || it.startsWith("WITH") })
        assertEquals(beforeRegular, retention.stored(regular))
        assertEquals(beforeSuspected, retention.storedSuspected(suspected))
        assertTrue(f.repository.fileExif(regular.flatMap { it.files }.map { it.path }).isEmpty())
        assertEquals(4L, presentCleanupResults(snapshot, null).possibleDuplicateFiles)
    }

    @Test
    fun waitsForScanLockWithoutHoldingDatabaseLaneAndLoadsCompletedInventory() = concurrencyTest { f ->
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val scan = launch {
            f.repository.scanning {
                entered.complete(Unit)
                release.await()
                f.repository.seedRoot(f.root)
                f.repository.recordListing(f.root, DirectoryListing(emptyList(),
                    listOf(DirectoryListing.Entry(f.root / "IMG.jpg", 100, 1))), 2)
            }
        }
        entered.await()
        val load = async { CleanupResultsService(f.repository,
            RetentionScoreService(f.repository, dispatcher = f.dispatcher), f.dispatcher).load() }
        runCurrent()
        assertFalse(load.isCompleted)
        assertEquals(0L, f.repository.summary().fileCount, "Waiting must not retain the database lane")
        release.complete(Unit)
        scan.join()
        assertEquals(1L, load.await().inventory.fileCount)
        assertEquals(1L, load.await().analysis.inventory.all.totalFiles)
    }

    @Test
    fun emptyDatabaseIsARealZeroSnapshot() = concurrencyTest { f ->
        val snapshot = CleanupResultsService(f.repository,
            RetentionScoreService(f.repository, dispatcher = f.dispatcher), f.dispatcher).load()
        assertEquals(0L, snapshot.inventory.fileCount)
        assertEquals(0L, snapshot.inventory.totalBytes)
        assertEquals(0L, snapshot.analysis.inventory.all.possibleDuplicateFiles)
        assertEquals(null, snapshot.analysis.classificationContext)
    }
}
