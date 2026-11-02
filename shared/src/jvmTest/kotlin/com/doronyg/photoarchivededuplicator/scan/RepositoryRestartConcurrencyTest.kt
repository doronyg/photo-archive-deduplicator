package com.doronyg.photoarchivededuplicator.scan

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.doronyg.photoarchivededuplicator.IoLane
import com.doronyg.photoarchivededuplicator.db.FilesDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path.Companion.toPath
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepositoryRestartConcurrencyTest {
    @Test
    fun cancelledMutatingTransactionSurvivesDriverReopenAndScannerResume() = runBlocking {
        withTimeout(10_000) {
            val directory = File("build/test-restart-fixtures/${UUID.randomUUID()}").absoluteFile
            val archive = File(directory, "archive")
            check(archive.mkdirs())
            File(archive, "old.jpg").writeText("old")
            val root = archive.path.toPath()
            val url = "jdbc:sqlite:${File(directory, "inventory.db")}"
            val lane = IoLane.dedicated("restart-db-first")
            val driver = lane.run("open first connection") {
                ConcurrencyTestDriver(JdbcSqliteDriver(url, schema = FilesDatabase.Schema.synchronous()))
            }
            val repository = DirectoryRepository(createFilesDatabase(driver), databaseLane = lane)
            try {
                repository.beginScanSession(root)
                repository.recordListing(root, DirectoryListing(emptyList(), listOf(
                    DirectoryListing.Entry(root / "old.jpg", 3, 10, 5),
                )), 20)
                repository.markInProgress(root)
                val oldFiles = repository.allFiles()
                val oldDirectory = repository.directory(root)
                File(archive, "new.jpg").writeText("new photo")
                driver.pauseAfter = ::isRecordSuccess
                val interrupted = async {
                    repository.recordListing(root, DirectoryListing(emptyList(), listOf(
                        DirectoryListing.Entry(root / "new.jpg", 9, 30, 25),
                    )), 40)
                }
                try {
                    driver.entered.await()
                    interrupted.cancelAndJoin()
                    assertTrue(interrupted.isCancelled)
                    lane.run("verify transaction ended") { assertNull(driver.currentTransaction()) }
                } finally {
                    driver.release.complete(Unit)
                    interrupted.cancelAndJoin()
                }
                lane.close { driver.close() }

                val reopenedLane = IoLane.dedicated("restart-db-second")
                val filesystem = IoLane.dedicated("restart-filesystem")
                val reopenedDriver = reopenedLane.run("reopen saved database") {
                    JdbcSqliteDriver(url, schema = FilesDatabase.Schema.synchronous())
                }
                val reopened = DirectoryRepository(createFilesDatabase(reopenedDriver), databaseLane = reopenedLane)
                try {
                    assertEquals(oldFiles, reopened.allFiles())
                    assertEquals(oldDirectory, reopened.directory(root))
                    assertEquals(ScanSessionRecord(root, 0), reopened.currentScanSession())
                    DirectoryScanner(FileSystem.SYSTEM, reopened, filesystemLane = filesystem).resume()
                    assertEquals(DirectoryStatus.COMPLETED, reopened.directory(root)?.status)
                    assertEquals(listOf("new.jpg", "old.jpg"), reopened.allFiles().map { it.name })
                    assertEquals(listOf(9L, 3L), reopened.allFiles().map { it.sizeBytes })
                    assertEquals(2, reopened.directory(root)?.fileCount)
                } finally {
                    filesystem.close {}
                    reopenedLane.close { reopenedDriver.close() }
                }
            } finally {
                driver.release.complete(Unit)
                lane.close { driver.close() }
                directory.deleteRecursively()
            }
        }
    }
}
