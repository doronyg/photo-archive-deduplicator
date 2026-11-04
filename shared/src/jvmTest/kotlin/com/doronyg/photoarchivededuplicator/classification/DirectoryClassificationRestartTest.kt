package com.doronyg.photoarchivededuplicator.classification

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.doronyg.photoarchivededuplicator.allUiChoices
import com.doronyg.photoarchivededuplicator.db.FilesDatabase
import com.doronyg.photoarchivededuplicator.scan.DirectoryRepository
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

class DirectoryClassificationRestartTest {
    @Test
    fun successfulProfilesAndOllamaSettingsSurviveActualSqliteCloseAndReopen() = runTest {
        val directory = File("build/test-directory-classification/${UUID.randomUUID()}").absoluteFile
        check(directory.mkdirs())
        val url = "jdbc:sqlite:${File(directory, "test.db")}"
        val io = TrackingIoDispatcher(StandardTestDispatcher(testScheduler))
        val context = allUiChoices.ollama.context()
        val path = "/写真/a\nb".toPath()
        val record = DirectoryClassificationRecord(path, path.toString(), DirectoryClassification.UNKNOWN, context, 123)
        val other = record.copy(context = context.copy(model = "other-test-model"),
            classification = DirectoryClassification.BACKUP, classifiedAt = 456)
        try {
            val driver = withContext(io) { JdbcSqliteDriver(url, schema = FilesDatabase.Schema.synchronous()) }
            try {
                val repository = DirectoryRepository(createFilesDatabase(IoGuardedDriver(driver, io)), io)
                repository.saveDirectoryClassification(record)
                repository.saveDirectoryClassification(other)
                repository.saveUiChoices(allUiChoices)
            } finally {
                withContext(io) { driver.close() }
            }
            val reopened = withContext(io) { JdbcSqliteDriver(url, schema = FilesDatabase.Schema.synchronous()) }
            try {
                val repository = DirectoryRepository(createFilesDatabase(IoGuardedDriver(reopened, io)), io)
                assertEquals(listOf(record), repository.directoryClassifications(listOf(path), context))
                assertEquals(listOf(other), repository.directoryClassifications(listOf(path), other.context))
                assertEquals(allUiChoices, repository.loadUiChoices())
                assertTrue(repository.allDirectories().isEmpty())
                assertTrue(repository.allFiles().isEmpty())
            } finally {
                withContext(io) { reopened.close() }
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
