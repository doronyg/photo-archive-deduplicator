package com.doronyg.photoarchivededuplicator.scan

import com.doronyg.photoarchivededuplicator.db.FilesDatabase
import com.doronyg.photoarchivededuplicator.UiChoices
import com.doronyg.photoarchivededuplicator.allUiChoices
import com.doronyg.photoarchivededuplicator.classification.*
import com.doronyg.photoarchivededuplicator.exif.*
import com.doronyg.photoarchivededuplicator.retention.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SchemaMigrationTest {
    @Test
    fun migratingHistoricalVersionOnePreservesInventoryAndSupportsCurrentQueries() = runTest {
        val driver = createMigrationTestDriver(migrate = false)
        val io = TrackingIoDispatcher(StandardTestDispatcher(testScheduler))
        val guarded = IoGuardedDriver(driver, io)
        val root = "/archive".toPath()
        val child = root / "pending"
        val file = root / "existing.jpg"
        try {
            withContext(io) {
                // DDL from HEAD's released ScanDirectory.sq and ScanFile.sq, not current
                // Schema.create(): v1 had no exclusions, creation times, or sessions.
                for (statement in historicalVersionOneSchema) guarded.execute(null, statement, 0).await()
                guarded.execute(null, """
                    INSERT INTO scanDirectory VALUES
                    ('/archive', NULL, 'COMPLETED', 0, 1, 123, 789),
                    ('/archive/pending', '/archive', 'IN_PROGRESS', 2, 0, 456, 700)
                """.trimIndent(), 0).await()
                guarded.execute(null, """
                    INSERT INTO scanFile VALUES
                    ('/archive/existing.jpg', '/archive', 'existing.jpg', 'jpg', 42, 456, 789)
                """.trimIndent(), 0).await()
                assertEquals(7L, FilesDatabase.Schema.version)
                guarded.execute(null, "PRAGMA user_version = 1", 0).await()
            }
            // Uses the same schema constructor as production desktop. Android's driver
            // calls the same generated migration via SQLiteOpenHelper.onUpgrade.
            val upgradedDriver = withContext(io) { createMigrationTestDriver(migrate = true) }
            try {
                val reopened = DirectoryRepository(createFilesDatabase(IoGuardedDriver(upgradedDriver, io)), io)
                assertEquals(listOf(
                    DirectoryRecord(root, null, DirectoryStatus.COMPLETED, 0, 1, 123, 789),
                    DirectoryRecord(child, root, DirectoryStatus.IN_PROGRESS, 2, 0, 456, 700),
                ), reopened.allDirectories())
                assertEquals(listOf(FileRecord(file, root, "existing.jpg", "jpg", 42, 456, 789)),
                    reopened.allFiles())
                assertTrue(reopened.exclusions().isEmpty())
                reopened.addExclusion(root / "private", "Keep out")
                assertEquals(listOf(DirectoryExclusion(root / "private", "Keep out")), reopened.exclusions())
                assertNull(reopened.currentScanSession())
                assertEquals(UiChoices(), reopened.loadUiChoices())
                assertEquals(root, reopened.resumableScanSession().root)
                assertEquals(DirectoryStatus.COMPLETED, reopened.directory(root)!!.status)
                reopened.resetInterrupted(root)
                assertEquals(listOf(child), reopened.fetchPendingBatch(10))
                reopened.recordListing(child, DirectoryListing(emptyList(), listOf(
                    DirectoryListing.Entry(child / "existing.jpg", 42, 457, 100),
                )), 800)
                assertEquals(100L, reopened.file(child / "existing.jpg")!!.createdAt)
                assertEquals(1, reopened.duplicateCandidates(root).size)
                reopened.checkpointScanSession(ScanSessionRecord(root, 12_345))
                assertEquals(12_345L, reopened.currentScanSession()!!.activeMillis)
                assertFalse(reopened.currentScanSession()!!.finished)
            } finally {
                upgradedDriver.close()
            }
        } finally {
            driver.close()
        }
    }

    @Test
    fun migratingInstalledVersionTwoAddsPreferencesWithoutChangingInventory() = runTest {
        val driver = createMigrationTestDriver(migrate = false)
        val io = TrackingIoDispatcher(StandardTestDispatcher(testScheduler))
        val guarded = IoGuardedDriver(driver, io)
        val root = "/archive".toPath()
        try {
            withContext(io) {
                // Installed v2 placed createdAt before lastScanned. Named queries must
                // work with that layout too; v3 must add preferences without rewriting it.
                for (statement in historicalVersionOneSchema) {
                    guarded.execute(null, statement.replace(
                        "lastScanned INTEGER NOT NULL", "createdAt INTEGER, lastScanned INTEGER NOT NULL",
                    ), 0).await()
                }
                for (statement in listOf(
                    "CREATE TABLE directoryExclusion (path TEXT NOT NULL PRIMARY KEY, reason TEXT)",
                    "CREATE INDEX scanFileNameSizeIndex ON scanFile(name COLLATE BINARY, sizeBytes)",
                    """CREATE TABLE scanSession (
                        root TEXT NOT NULL PRIMARY KEY, activeMillis INTEGER NOT NULL DEFAULT 0,
                        selected INTEGER NOT NULL DEFAULT 0, finished INTEGER NOT NULL DEFAULT 0
                    )""",
                    "PRAGMA user_version = 2",
                )) guarded.execute(null, statement, 0).await()
            }
            val repository = DirectoryRepository(createFilesDatabase(guarded), io)
            repository.beginScanSession(root)
            repository.recordListing(root, DirectoryListing(emptyList(), listOf(
                DirectoryListing.Entry(root / "existing.jpg", 42, 456, 100),
            )), 789)
            repository.addExclusion(root / "private", "Keep out")
            repository.checkpointScanSession(ScanSessionRecord(root, 12_345, finished = true))
            val directories = repository.allDirectories()
            val files = repository.allFiles()
            val exclusions = repository.exclusions()
            val session = repository.currentScanSession()

            val reopenedDriver = withContext(io) { createMigrationTestDriver(migrate = true) }
            try {
                val reopened = DirectoryRepository(createFilesDatabase(IoGuardedDriver(reopenedDriver, io)), io)
                assertEquals(directories, reopened.allDirectories())
                assertEquals(files, reopened.allFiles())
                assertEquals(exclusions, reopened.exclusions())
                assertEquals(session, reopened.currentScanSession())
                assertTrue(reopened.currentScanSession()!!.finished)
                assertEquals(UiChoices(), reopened.loadUiChoices())
                val choices = UiChoices(scanRoot = "/saved")
                reopened.saveUiChoices(choices)
                assertEquals(choices, reopened.loadUiChoices())
            } finally {
                reopenedDriver.close()
            }
        } finally {
            driver.close()
        }
    }

    @Test
    fun migratingVersionSixAddsClassificationsWithoutChangingExistingData() = runTest {
        val driver = createMigrationTestDriver(migrate = false)
        val io = TrackingIoDispatcher(StandardTestDispatcher(testScheduler))
        val guarded = IoGuardedDriver(driver, io)
        val root = "/archive".toPath()
        try {
            withContext(io) {
                FilesDatabase.Schema.create(guarded).await()
                guarded.execute(null, "DROP TABLE directoryAiClassification", 0).await()
                guarded.execute(null, "PRAGMA user_version = 6", 0).await()
            }
            val repository = DirectoryRepository(createFilesDatabase(guarded), io)
            repository.beginScanSession(root)
            repository.recordListing(root, DirectoryListing(emptyList(), listOf(
                DirectoryListing.Entry(root / "雪.jpg", 42, 456, 100),
            )), 789)
            repository.addExclusion(root / "private", "Keep out")
            repository.checkpointScanSession(ScanSessionRecord(root, 12_345, finished = true))
            repository.saveUiChoices(allUiChoices)
            val file = repository.allFiles().single()
            val exif = ExifRecord(ExifInput.from(file), ExifReadResult(ExifStatus.NO_EXIF), 800)
            val score = FileScore(FileScoreInput.from(file), ScoreIdentity("retention", "rules", "1", "default"),
                0.5, listOf(ScoreFactor("metadata", 0.5, "Preserved")), 801)
            assertTrue(repository.saveFileExif(exif))
            assertTrue(repository.saveFileScore(score))
            val directories = repository.allDirectories()
            val files = repository.allFiles()
            val exclusions = repository.exclusions()
            val session = repository.currentScanSession()
            val reopenedDriver = withContext(io) { createMigrationTestDriver(migrate = true) }
            try {
                val reopened = DirectoryRepository(createFilesDatabase(IoGuardedDriver(reopenedDriver, io)), io)
                assertEquals(7L, FilesDatabase.Schema.version)
                assertEquals(directories, reopened.allDirectories())
                assertEquals(files, reopened.allFiles())
                assertEquals(exclusions, reopened.exclusions())
                assertEquals(session, reopened.currentScanSession())
                assertEquals(allUiChoices, reopened.loadUiChoices())
                assertEquals(listOf(exif), reopened.fileExif(listOf(file.path)))
                assertEquals(listOf(score), reopened.fileScores(listOf(file.path)))
                val context = DirectoryClassificationContext("http://test.invalid", "test-model")
                assertTrue(reopened.directoryClassifications(listOf(root), context).isEmpty())
                val classification = DirectoryClassificationRecord(root, root.toString(),
                    DirectoryClassification.UNKNOWN, context, 900)
                reopened.saveDirectoryClassification(classification)
                assertEquals(listOf(classification), reopened.directoryClassifications(listOf(root), context))
            } finally {
                reopenedDriver.close()
            }
        } finally {
            driver.close()
        }
    }

    private val historicalVersionOneSchema = listOf(
        """CREATE TABLE scanDirectory (
            path TEXT NOT NULL PRIMARY KEY, parentPath TEXT, status TEXT NOT NULL,
            retryCount INTEGER NOT NULL DEFAULT 0, fileCount INTEGER NOT NULL DEFAULT 0,
            modifiedAt INTEGER, lastScanned INTEGER
        )""",
        "CREATE INDEX scanDirectoryStatusIndex ON scanDirectory(status)",
        "CREATE INDEX scanDirectoryParentIndex ON scanDirectory(parentPath)",
        """CREATE TABLE scanFile (
            path TEXT NOT NULL PRIMARY KEY, directoryPath TEXT NOT NULL, name TEXT NOT NULL,
            extension TEXT, sizeBytes INTEGER NOT NULL, modifiedAt INTEGER, lastScanned INTEGER NOT NULL
        )""",
        "CREATE INDEX scanFileDirectoryIndex ON scanFile(directoryPath)",
    )
}
