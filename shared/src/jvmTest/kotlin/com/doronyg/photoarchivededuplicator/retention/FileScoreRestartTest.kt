package com.doronyg.photoarchivededuplicator.retention

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.doronyg.photoarchivededuplicator.db.FilesDatabase
import com.doronyg.photoarchivededuplicator.scan.DirectoryListing
import com.doronyg.photoarchivededuplicator.scan.DirectoryRepository
import com.doronyg.photoarchivededuplicator.scan.DirectoryStatus
import com.doronyg.photoarchivededuplicator.scan.IoGuardedDriver
import com.doronyg.photoarchivededuplicator.scan.TrackingIoDispatcher
import com.doronyg.photoarchivededuplicator.scan.createFilesDatabase
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileScoreRestartTest {
    @Test
    fun scoresAndOrderedFactorsSurviveRealCloseAndReopen() = runTest {
        val directory = File("build/test-file-scores/${UUID.randomUUID()}").absoluteFile
        check(directory.mkdirs())
        val io = TrackingIoDispatcher(StandardTestDispatcher(testScheduler))
        val url = "jdbc:sqlite:${File(directory, "scores.db")}"
        val root = "/archive".toPath()
        val input = FileScoreInput(root / "photo.jpg", root, 42, 123, 100)
        val scores = listOf("retention", "quality").map {
            FileScore(input, ScoreIdentity(it, "rules", "1", "default"), 0.75,
                listOf(ScoreFactor("z", 0.5, "First\n雪"), ScoreFactor("a", 0.25, "Second")), 300)
        }
        try {
            val first = withContext(io) { JdbcSqliteDriver(url, schema = FilesDatabase.Schema.synchronous()) }
            try {
                val repository = DirectoryRepository(createFilesDatabase(IoGuardedDriver(first, io)), io)
                repository.seedRoot(root)
                repository.recordListing(root, DirectoryListing(emptyList(), listOf(
                    DirectoryListing.Entry(input.path, input.sizeBytes, input.modifiedAt, input.createdAt),
                )), 200)
                scores.forEach { assertTrue(repository.saveFileScore(it)) }
            } finally {
                withContext(io) { first.close() }
            }
            val second = withContext(io) { JdbcSqliteDriver(url, schema = FilesDatabase.Schema.synchronous()) }
            try {
                val repository = DirectoryRepository(createFilesDatabase(IoGuardedDriver(second, io)), io)
                assertEquals(scores.toSet(), repository.fileScores(listOf(input.path)).toSet())
                assertEquals(listOf(scores.first()), repository.fileScores(listOf(input.path), scores.first().identity))
            } finally {
                withContext(io) { second.close() }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun populatedHistoricalVersionThreeAutomaticallyUpgradesWithoutChangingAnyExistingRow() = runTest {
        val directory = File("build/test-file-scores-migration/${UUID.randomUUID()}").absoluteFile
        check(directory.mkdirs())
        val io = TrackingIoDispatcher(StandardTestDispatcher(testScheduler))
        val url = "jdbc:sqlite:${File(directory, "historical.db")}"
        val tables = mapOf("scanDirectory" to 7, "scanFile" to 8, "directoryExclusion" to 2,
            "scanSession" to 4, "uiPreference" to 2)
        suspend fun snapshot(driver: SqlDriver): Map<String, List<List<String?>>> = withContext(io) {
            tables.mapValues { (table, columns) ->
                driver.executeQuery(null, "SELECT * FROM $table ORDER BY 1", { cursor ->
                    val rows = mutableListOf<List<String?>>()
                    while (cursor.next().value) rows += (0 until columns).map { cursor.getString(it) }
                    QueryResult.Value(rows.toList())
                }, 0).await()
            }
        }
        try {
            val old = withContext(io) { JdbcSqliteDriver(url) }
            val before = try {
                withContext(io) {
                    for (statement in historicalVersionThreeSchema) old.execute(null, statement, 0).await()
                }
                snapshot(old)
            } finally {
                withContext(io) { old.close() }
            }
            val upgraded = withContext(io) { JdbcSqliteDriver(url, schema = FilesDatabase.Schema.synchronous()) }
            try {
                assertEquals(10L, FilesDatabase.Schema.version)
                assertEquals(before, snapshot(upgraded))
                val repository = DirectoryRepository(createFilesDatabase(IoGuardedDriver(upgraded, io)), io)
                val root = "/archive".toPath()
                assertEquals(1, repository.duplicateCandidates(root).size)
                assertEquals(2, repository.directory(root / "retry")!!.retryCount)
                assertEquals(DirectoryStatus.PENDING, repository.directory(root / "retry")!!.status)
                assertEquals(DirectoryStatus.IN_PROGRESS, repository.directory(root / "working")!!.status)
                assertEquals(12_345L, repository.currentScanSession()!!.activeMillis)
                assertEquals("/saved", repository.loadUiChoices().scanRoot)
                val score = FileScore(FileScoreInput.from(repository.file(root / "same.jpg")!!),
                    ScoreIdentity("retention", "rules", "1", "default"), 0.5,
                    listOf(ScoreFactor("metadata", 0.5, "Migrated file")), 900)
                assertTrue(repository.saveFileScore(score))
                assertEquals(listOf(score), repository.fileScores(listOf(score.input.path)))
                assertEquals(before, snapshot(upgraded))
            } finally {
                withContext(io) { upgraded.close() }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private val historicalVersionThreeSchema = listOf(
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
        "PRAGMA user_version = 3",
    )
}
