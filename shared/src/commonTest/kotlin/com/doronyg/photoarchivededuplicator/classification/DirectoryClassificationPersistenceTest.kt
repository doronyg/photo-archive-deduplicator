package com.doronyg.photoarchivededuplicator.classification

import com.doronyg.photoarchivededuplicator.scan.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import okio.Path.Companion.toPath
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DirectoryClassificationPersistenceTest {
    private val context = DirectoryClassificationContext(
        "http://test.invalid:11434", "test-model",
    )

    private fun record(path: String = "/archive/雪\nfolder") = DirectoryClassificationRecord(
        path.toPath(), path, DirectoryClassification.PREFERRED, context, 123,
    )

    @Test
    fun resultsViewerReadsAllProfilesAndPromptVersionsWithoutInventoryOrFilesystemAccess() = concurrencyTest { f ->
        val current = record("/archive/טיול")
        val older = current.copy(context = context.copy(promptVersion = "1"), classifiedAt = 100)
        val otherModel = current.copy(context = context.copy(model = "other-model"),
            classification = DirectoryClassification.BACKUP, classifiedAt = 456)
        val otherPath = record("/archive/A").copy(classification = DirectoryClassification.UNKNOWN)
        val expected = listOf(otherPath, otherModel, current, older)
        expected.reversed().forEach { f.repository.saveDirectoryClassification(it) }
        f.fs.onBlockingCall = { error("Viewing saved AI results must not access the archive") }
        assertEquals(expected, f.repository.allDirectoryClassifications())
        assertTrue(f.repository.allFiles().isEmpty())
    }

    @Test
    fun allSuccessesIncludingUnknownRoundTripWithoutInventoryAndProfilesNeverMix() = concurrencyTest { f ->
        val original = record()
        assertTrue(f.repository.directoryClassifications(emptyList(), context).isEmpty())
        assertTrue(f.repository.directoryClassifications(listOf(original.directoryPath), context).isEmpty())
        val profiles = listOf(context, context.copy(baseUrl = "http://other.invalid"),
            context.copy(model = "other-model"), context.copy(promptVersion = "1"))
        for ((index, profile) in profiles.withIndex()) {
            f.repository.saveDirectoryClassification(original.copy(context = profile, classifiedAt = index.toLong()))
        }
        for ((index, profile) in profiles.withIndex()) {
            assertEquals(listOf(original.copy(context = profile, classifiedAt = index.toLong())),
                f.repository.directoryClassifications(listOf(original.directoryPath), profile))
        }
        for (classification in DirectoryClassification.entries) {
            val replacement = original.copy(classification = classification, classifiedAt = 456)
            f.repository.saveDirectoryClassification(replacement)
            assertEquals(listOf(replacement),
                f.repository.directoryClassifications(listOf(original.directoryPath), context))
        }
        assertTrue(f.repository.allDirectories().isEmpty())
        assertTrue(f.repository.allFiles().isEmpty())
    }

    @Test
    fun identityNormalizesButOriginalSpellingIsPreservedAndBadPathsAreRejected() = concurrencyTest { f ->
        val original = record("/archive/./parent/../雪\nfolder")
        f.repository.saveDirectoryClassification(original)
        val expected = original.copy(directoryPath = original.directoryPath.normalized())
        assertEquals(listOf(expected), f.repository.directoryClassifications(
            listOf(original.directoryPath, expected.directoryPath), context))
        for (invalid in listOf(original.copy(originalPath = "/different"),
            original.copy(directoryPath = "relative".toPath(), originalPath = "relative"),
            original.copy(originalPath = "relative"))) {
            assertFailsWith<IllegalArgumentException> { f.repository.saveDirectoryClassification(invalid) }
        }
        assertFailsWith<IllegalArgumentException> {
            f.repository.directoryClassifications(listOf("relative".toPath()), context)
        }
        assertEquals(listOf(expected),
            f.repository.directoryClassifications(listOf(expected.directoryPath), context))
    }

    @Test
    fun legacyRootBasedResultsRemainStoredButAreNotReusedForTheNewPrompt() = concurrencyTest { f ->
        f.lane.run("insert legacy classification") {
            f.driver.execute(null, """
                INSERT INTO directoryAiClassification VALUES
                ('/archive', 'http://test.invalid:11434', 'test-model', '1', '8:/archive', '/archive', 'PREFERRED', 123)
            """.trimIndent(), 0).await()
        }
        val original = record("/archive")
        assertTrue(f.repository.directoryClassifications(listOf(original.directoryPath), context).isEmpty())
        f.repository.saveDirectoryClassification(original)
        assertEquals(listOf(original), f.repository.directoryClassifications(listOf(original.directoryPath), context))
        val legacy = f.lane.run("read preserved legacy classification") {
            createFilesDatabase(f.driver).directoryAiClassificationQueries.selectForPaths(
                listOf("/archive"), context.baseUrl, context.model, "1", "8:/archive",
            ).executeAsList()
        }
        assertEquals(1, legacy.size)
    }

    @Test
    fun schemaRejectsUnrecognizedClassificationLabels() = concurrencyTest { f ->
        f.lane.run("assert classification constraint") {
            assertFailsWith<Exception> {
                f.driver.execute(null, """
                    INSERT INTO directoryAiClassification VALUES
                    ('/archive', 'http://test.invalid', 'test-model', '1', '', '/archive', 'preferred', 123)
                """.trimIndent(), 0).await()
            }
        }
        assertTrue(f.repository.directoryClassifications(listOf("/archive".toPath()), context).isEmpty())
    }

    @Test
    fun largeReadsAreDeduplicatedAndChunkedAtFourHundred() = concurrencyTest { f ->
        val records = (0 until 1100).map { record("/archive/$it") }
        records.forEach { f.repository.saveDirectoryClassification(it) }
        val start = f.driver.statements.size
        val paths = records.map { it.directoryPath }
        assertEquals(records.toSet(), f.repository.directoryClassifications(paths + paths, context).toSet())
        val reads = f.driver.statements.drop(start)
        assertEquals(3, reads.size)
        assertTrue(reads.all { it.startsWith("SELECT") })
    }

    @Test
    fun failedRefreshLeavesPreviousSuccessAndTransactionUsable() = concurrencyTest { f ->
        val original = record()
        f.repository.saveDirectoryClassification(original)
        f.driver.beforeStatement = {
            if (it.startsWith("INSERT OR REPLACE INTO directoryAiClassification")) error("disk full")
        }
        assertFailsWith<IllegalStateException> {
            f.repository.saveDirectoryClassification(original.copy(classifiedAt = 999))
        }
        f.driver.beforeStatement = {}
        assertNull(f.driver.currentTransaction())
        assertEquals(listOf(original),
            f.repository.directoryClassifications(listOf(original.directoryPath), context))
        f.repository.saveDirectoryClassification(original.copy(classifiedAt = 1000))
    }

    @Test
    fun cancelledRefreshRollsBackBeforeQueuedReadReusesLane() = concurrencyTest { f ->
        val original = record()
        f.repository.saveDirectoryClassification(original)
        f.driver.pauseAfter = { it.startsWith("INSERT OR REPLACE INTO directoryAiClassification") }
        val save = launch { f.repository.saveDirectoryClassification(original.copy(classifiedAt = 999)) }
        f.driver.entered.await()
        val read = async { f.repository.directoryClassifications(listOf(original.directoryPath), context) }
        runCurrent()
        assertFalse(read.isCompleted)
        save.cancelAndJoin()
        assertEquals(listOf(original), read.await())
        assertNull(f.driver.currentTransaction())
    }
}
