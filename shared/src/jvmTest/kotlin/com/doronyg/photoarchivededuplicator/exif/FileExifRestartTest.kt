package com.doronyg.photoarchivededuplicator.exif

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.doronyg.photoarchivededuplicator.db.FilesDatabase
import com.doronyg.photoarchivededuplicator.scan.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath
import java.io.File
import java.util.UUID
import kotlin.test.*

class FileExifRestartTest {
    @Test
    fun populatedVersionFourAutomaticallyUpgradesAndExifSurvivesReopenWithoutChangingExistingRows() =
        verifyUpgrade(fromVersion = 4)

    @Test
    fun populatedVersionFiveAddsSizeIndexAndPreservesExifInventoryScoresPreferencesAndSessions() =
        verifyUpgrade(fromVersion = 5)

    private fun verifyUpgrade(fromVersion: Long) = runTest {
        val directory = File("build/test-file-exif-migration/${UUID.randomUUID()}").absoluteFile
        check(directory.mkdirs())
        val io = TrackingIoDispatcher(StandardTestDispatcher(testScheduler))
        val url = "jdbc:sqlite:${File(directory, "historical.db")}"
        val tables = mapOf("scanDirectory" to 7, "scanFile" to 8, "directoryExclusion" to 2,
            "scanSession" to 4, "uiPreference" to 2, "fileScore" to 12, "fileScoreFactor" to 5) +
            (if (fromVersion == 5L) mapOf("fileExif" to 18) else emptyMap())
        suspend fun snapshot(driver: SqlDriver): Map<String, List<List<String?>>> = withContext(io) {
            tables.mapValues { (table, columns) ->
                driver.executeQuery(null, "SELECT * FROM $table ORDER BY 1, 2", { cursor ->
                    val rows = mutableListOf<List<String?>>()
                    while (cursor.next().value) rows += (0 until columns).map { cursor.getString(it) }
                    QueryResult.Value(rows.toList())
                }, 0).await()
            }
        }
        val record = ExifRecord(ExifInput("/archive/same.jpg".toPath(), "/archive".toPath(), 42, 456, 100),
            ExifReadResult(ExifStatus.EXTRACTED,
                ExifMetadata("2024:01:02 03:04:05", "+01:00", "009", "雪", "Camera", 8, 6000, 4000)), 900)
        try {
            val old = withContext(io) { JdbcSqliteDriver(url) }
            val before = try {
                withContext(io) {
                    for (statement in historicalVersionFourSchema) old.execute(null, statement, 0).await()
                    if (fromVersion == 5L) {
                        FilesDatabase.Schema.migrate(old, 4, 5).await()
                        old.execute(null, "PRAGMA user_version = 5", 0).await()
                    }
                }
                if (fromVersion == 5L) {
                    val repository = DirectoryRepository(createFilesDatabase(IoGuardedDriver(old, io)), io)
                    assertTrue(repository.saveFileExif(record))
                }
                snapshot(old)
            } finally {
                withContext(io) { old.close() }
            }
            val upgraded = withContext(io) { JdbcSqliteDriver(url, schema = FilesDatabase.Schema.synchronous()) }
            try {
                assertEquals(6L, FilesDatabase.Schema.version)
                assertEquals(before, snapshot(upgraded))
                withContext(io) {
                    val columns = upgraded.executeQuery(null, "PRAGMA index_info(scanFileSizeIndex)", { cursor ->
                        val names = mutableListOf<String?>()
                        while (cursor.next().value) names += cursor.getString(2)
                        QueryResult.Value(names.toList())
                    }, 0).await()
                    assertEquals(listOf("sizeBytes"), columns)
                }
                val repository = DirectoryRepository(createFilesDatabase(IoGuardedDriver(upgraded, io)), io)
                assertEquals(if (fromVersion == 5L) listOf(record) else emptyList(),
                    repository.fileExif(listOf(record.input.path)))
                assertTrue(repository.saveFileExif(record))
                assertEquals(before, snapshot(upgraded))
            } finally {
                withContext(io) { upgraded.close() }
            }
            val reopened = withContext(io) { JdbcSqliteDriver(url, schema = FilesDatabase.Schema.synchronous()) }
            try {
                val repository = DirectoryRepository(createFilesDatabase(IoGuardedDriver(reopened, io)), io)
                assertEquals(listOf(record), repository.fileExif(listOf(record.input.path)))
                assertEquals(before, snapshot(reopened))
                assertEquals(1, repository.duplicateCandidates("/archive".toPath()).size)
                assertEquals(2, repository.directory("/archive/retry".toPath())!!.retryCount)
                assertEquals(DirectoryStatus.PENDING, repository.directory("/archive/retry".toPath())!!.status)
                assertEquals(DirectoryStatus.IN_PROGRESS, repository.directory("/archive/working".toPath())!!.status)
                assertEquals(12_345L, repository.currentScanSession()!!.activeMillis)
                assertEquals("/saved", repository.loadUiChoices().scanRoot)
                val score = repository.fileScores(listOf(record.input.path)).single()
                assertEquals(listOf("z", "a"), score.factors.map { it.key })
                assertEquals(listOf("First\n雪", "Second"), score.factors.map { it.explanation })
            } finally {
                withContext(io) { reopened.close() }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private val historicalVersionFourSchema = listOf(
        """CREATE TABLE scanDirectory (
            path TEXT NOT NULL PRIMARY KEY, parentPath TEXT, status TEXT NOT NULL,
            retryCount INTEGER NOT NULL DEFAULT 0, fileCount INTEGER NOT NULL DEFAULT 0,
            modifiedAt INTEGER, lastScanned INTEGER
        )""",
        "CREATE INDEX scanDirectoryStatusIndex ON scanDirectory(status)",
        "CREATE INDEX scanDirectoryParentIndex ON scanDirectory(parentPath)",
        """CREATE TABLE scanFile (
            path TEXT NOT NULL PRIMARY KEY, directoryPath TEXT NOT NULL, name TEXT NOT NULL,
            extension TEXT, sizeBytes INTEGER NOT NULL, modifiedAt INTEGER,
            createdAt INTEGER, lastScanned INTEGER NOT NULL
        )""",
        "CREATE INDEX scanFileDirectoryIndex ON scanFile(directoryPath)",
        "CREATE INDEX scanFileNameSizeIndex ON scanFile(name COLLATE BINARY, sizeBytes)",
        "CREATE TABLE directoryExclusion (path TEXT NOT NULL PRIMARY KEY, reason TEXT)",
        """CREATE TABLE scanSession (
            root TEXT NOT NULL PRIMARY KEY, activeMillis INTEGER NOT NULL DEFAULT 0,
            selected INTEGER NOT NULL DEFAULT 0, finished INTEGER NOT NULL DEFAULT 0
        )""",
        "CREATE TABLE uiPreference (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)",
        """CREATE TABLE fileScore (
            id INTEGER NOT NULL PRIMARY KEY, filePath TEXT NOT NULL, directoryPath TEXT NOT NULL,
            inputSizeBytes INTEGER NOT NULL, inputModifiedAt INTEGER, inputCreatedAt INTEGER,
            scoreType TEXT NOT NULL, algorithmId TEXT NOT NULL, algorithmVersion TEXT NOT NULL,
            configurationId TEXT NOT NULL, value REAL NOT NULL, createdAt INTEGER NOT NULL,
            UNIQUE(filePath, scoreType, algorithmId, algorithmVersion, configurationId)
        )""",
        """CREATE TABLE fileScoreFactor (
            scoreId INTEGER NOT NULL REFERENCES fileScore(id), ordinal INTEGER NOT NULL,
            factorKey TEXT NOT NULL, contribution REAL NOT NULL, explanation TEXT NOT NULL,
            PRIMARY KEY(scoreId, ordinal)
        )""",
        """INSERT INTO scanDirectory VALUES
            ('/archive', NULL, 'COMPLETED', 0, 1, 123, 789),
            ('/archive/retry', '/archive', 'PENDING', 2, 1, 456, 700),
            ('/archive/working', '/archive', 'IN_PROGRESS', 1, 0, NULL, NULL),
            ('/archive/failed', '/archive', 'FAILED', 3, 0, 600, 800)""",
        """INSERT INTO scanFile VALUES
            ('/archive/same.jpg', '/archive', 'same.jpg', 'jpg', 42, 456, 100, 789),
            ('/archive/retry/same.jpg', '/archive/retry', 'same.jpg', 'jpg', 42, NULL, NULL, 700)""",
        "INSERT INTO directoryExclusion VALUES ('/archive/private', 'Keep out'), ('/other', NULL)",
        "INSERT INTO scanSession VALUES ('/archive', 12345, 1, 0), ('/finished', 67890, 0, 1)",
        "INSERT INTO uiPreference VALUES ('scanRoot', '/saved'), ('future-setting', 'preserve me')",
        """INSERT INTO fileScore VALUES
            (7, '/archive/same.jpg', '/archive', 42, 456, 100, 'retention', 'rules', '1', 'default', 0.75, 800),
            (8, '/archive/retry/same.jpg', '/archive/retry', 42, NULL, NULL, 'quality', 'model', '2', 'custom', 0.25, 801)""",
        "INSERT INTO fileScoreFactor VALUES (7, 0, 'z', 0.5, 'First\n雪'), (7, 1, 'a', 0.25, 'Second'), (8, 0, 'q', 0.25, 'Quality')",
        "PRAGMA user_version = 4",
    )
}
