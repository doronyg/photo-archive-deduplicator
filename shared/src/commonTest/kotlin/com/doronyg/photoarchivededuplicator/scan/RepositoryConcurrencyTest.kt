package com.doronyg.photoarchivededuplicator.scan

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RepositoryConcurrencyTest {
    private suspend fun seed(f: ConcurrencyFixture) {
        val r = f.repository
        r.seedRoot(f.root)
        r.recordListing(f.root, listing(f, "old"), 100)
        r.recordListing(f.root / "old", DirectoryListing(emptyList(), listOf(
            DirectoryListing.Entry(f.root / "old" / "same.jpg", 4, 12, 8),
        )), 101)
        r.recordFailure(f.root / "old", 3)
        r.markInProgress(f.root)
    }

    private fun listing(f: ConcurrencyFixture, child: String) = DirectoryListing(
        listOf(DirectoryListing.Entry(f.root / child, 0, 20)),
        listOf(
            DirectoryListing.Entry(f.root / "same.jpg", 4, 11, 7),
            DirectoryListing.Entry(f.root / "$child.jpg", 6, 13, 9),
        ),
    )

    @Test
    fun exceptionAfterDeletesAndInsertsRestoresEveryRecord() = concurrencyTest { f ->
        seed(f)
        val r = f.repository
        val dirs = r.allDirectories()
        val files = r.allFiles()
        val sentinel = IllegalStateException("after reconciliation mutations")
        val start = f.driver.statements.size
        f.driver.beforeStatement = { if (isRecordSuccess(it)) throw sentinel }

        assertEquals(sentinel.message, assertFailsWith<IllegalStateException> {
            r.recordListing(f.root, listing(f, "new"), 500)
        }.message)
        f.driver.beforeStatement = {}
        val executed = f.driver.statements.drop(start).dropLast(1)
        assertTrue(executed.any { it.startsWith("DELETE FROM scanDirectory") })
        assertTrue(executed.any { it.startsWith("DELETE FROM scanFile") })
        assertTrue(executed.any { it.startsWith("INSERT OR IGNORE INTO scanDirectory") })
        assertTrue(executed.any { it.startsWith("INSERT OR IGNORE INTO scanFile") })
        assertEquals(dirs, r.allDirectories())
        assertEquals(files, r.allFiles())
        assertEquals(DirectoryStatus.IN_PROGRESS, r.directory(f.root)?.status)
        assertNull(f.driver.currentTransaction())
        r.recordListing(f.root, listing(f, "new"), 501)
        assertEquals(listOf(f.root, f.root / "new"), r.allDirectories().map { it.path })
    }

    @Test
    fun cancellationAfterMutationsRollsBackBeforeQueuedReadersAndWriter() = concurrencyTest { f ->
        seed(f)
        val r = f.repository
        val dirs = r.allDirectories()
        val files = r.allFiles()
        val summaryBefore = r.summary()
        val duplicatesBefore = r.duplicateCandidates(f.root)
        f.driver.pauseAfter = ::isRecordSuccess
        val owner = launch { r.recordListing(f.root, listing(f, "new"), 500) }
        f.driver.entered.await()
        val count = f.driver.statements.size
        val snapshot = async { r.allDirectories() to r.allFiles() }
        val summary = async { r.summary() }
        val duplicates = async { r.duplicateCandidates(f.root) }
        val writer = launch { r.seedRoot(f.root / "queued") }
        runCurrent()
        assertEquals(count, f.driver.statements.size)
        assertTrue(listOf(snapshot, summary, duplicates, writer).none { it.isCompleted })

        owner.cancelAndJoin()
        assertEquals(dirs to files, snapshot.await())
        assertEquals(summaryBefore, summary.await())
        assertEquals(duplicatesBefore, duplicates.await())
        writer.join()
        assertNull(f.driver.currentTransaction())
        r.recordListing(f.root, listing(f, "new"), 501)
        assertEquals(listOf(f.root, f.root / "new", f.root / "queued"), r.allDirectories().map { it.path })
        assertNull(r.directory(f.root / "queued")?.parentPath)
        assertEquals(501L, r.file(f.root / "same.jpg")?.lastScanned)
    }

    @Test
    fun queuedBrowseAndSecondReconciliationSeeOnlyCompleteOrderedCommit() = concurrencyTest { f ->
        seed(f)
        val r = f.repository
        f.driver.pauseAfter = ::isRecordSuccess
        val first = launch { r.recordListing(f.root, listing(f, "new"), 500) }
        f.driver.entered.await()
        val count = f.driver.statements.size
        val files = async { r.filesIn(f.root) }
        val overviews = async { r.childOverviews(f.root) }
        val duplicates = async { r.duplicateCandidates(f.root) }
        val second = launch {
            r.recordListing(f.root, DirectoryListing(emptyList(), listOf(
                DirectoryListing.Entry(f.root / "z.jpg", 9, 99, 88),
                DirectoryListing.Entry(f.root / "a.jpg", 1, 33, 22),
            )), 600)
        }
        runCurrent()
        assertEquals(count, f.driver.statements.size)
        assertTrue(listOf(files, overviews, duplicates, second).none { it.isCompleted })
        f.driver.release.complete(Unit)
        first.join()
        assertEquals(listOf(f.root / "new.jpg", f.root / "same.jpg"), files.await().map { it.path })
        assertTrue(files.await().all { it.lastScanned == 500L })
        assertEquals(listOf(f.root / "new"), overviews.await().map { it.directory.path })
        assertTrue(duplicates.await().isEmpty())
        second.join()
        assertEquals(listOf(f.root / "a.jpg", f.root / "z.jpg"), r.filesIn(f.root).map { it.path })
        assertTrue(r.allFiles().all { it.lastScanned == 600L })
        assertEquals(listOf(f.root), r.allDirectories().map { it.path })
        assertNull(f.driver.currentTransaction())
    }

    @Test
    fun cancellingExclusionEditWaitingForDatabaseReleasesScanMutex() = concurrencyTest { f ->
        seed(f)
        val r = f.repository
        f.driver.pauseAfter = ::isRecordSuccess
        val owner = launch { r.recordListing(f.root, listing(f, "new"), 500) }
        f.driver.entered.await()
        val count = f.driver.statements.size
        val edit = launch { r.addExclusion(f.root / "cancelled") }
        runCurrent()
        assertFalse(edit.isCompleted)
        assertEquals(count, f.driver.statements.size)
        edit.cancelAndJoin()
        val next = launch { r.addExclusion(f.root / "next") }
        runCurrent()
        assertFalse(next.isCompleted)
        assertEquals(count, f.driver.statements.size)
        f.driver.release.complete(Unit)
        owner.join()
        next.join()
        assertEquals(listOf(f.root / "next"), r.exclusions().map { it.path })
    }
}
