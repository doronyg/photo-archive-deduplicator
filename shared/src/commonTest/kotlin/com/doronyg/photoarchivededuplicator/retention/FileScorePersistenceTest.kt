package com.doronyg.photoarchivededuplicator.retention

import com.doronyg.photoarchivededuplicator.scan.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class FileScorePersistenceTest {
    private val identity = ScoreIdentity("retention", "rules", "1", "default")

    private suspend fun seed(f: ConcurrencyFixture): FileScore {
        f.repository.seedRoot(f.root)
        f.repository.recordListing(f.root, DirectoryListing(emptyList(), listOf(
            DirectoryListing.Entry(f.root / "photo.jpg", 42, 123, 100),
        )), 200)
        return FileScore(FileScoreInput.from(f.repository.file(f.root / "photo.jpg")!!),
            identity, 0.75, listOf(ScoreFactor("first", 0.5, "First"), ScoreFactor("second", 0.25, "Second")), 300)
    }

    @Test
    fun identitiesCoexistAndRecomputeReplacesOnlyItsOwnOrderedFactors() = concurrencyTest { f ->
        val original = seed(f)
        val others = listOf(
            identity.copy(type = "quality"),
            identity.copy(algorithmId = "other"),
            identity.copy(algorithmVersion = "2"),
            identity.copy(configurationId = "custom"),
        ).map { original.copy(identity = it) }
        for (score in others + original) assertTrue(f.repository.saveFileScore(score))
        val replacement = original.copy(value = 0.1, createdAt = 400,
            factors = listOf(ScoreFactor("second", 0.1, "Revised"), ScoreFactor("second", 0.0, "Repeated key")))
        assertTrue(f.repository.saveFileScore(replacement))
        assertEquals(listOf(replacement), f.repository.fileScores(listOf(original.input.path), identity))
        assertEquals((others + replacement).toSet(), f.repository.fileScores(listOf(original.input.path)).toSet())
        assertTrue(f.repository.fileScores(emptyList()).isEmpty())
        assertTrue(f.repository.fileScores(listOf(original.input.path), identity.copy(type = "missing")).isEmpty())
        assertTrue(f.repository.saveFileScore(replacement.copy(factors = emptyList())))
        assertTrue(f.repository.fileScores(listOf(original.input.path), identity).single().factors.isEmpty())
    }

    @Test
    fun everyRelevantInputMustMatchButLastScannedIsNotAnInput() = concurrencyTest { f ->
        val original = seed(f)
        assertTrue(f.repository.saveFileScore(original))
        val input = original.input
        val staleInputs = listOf(
            input.copy(directoryPath = f.root / "other"),
            input.copy(sizeBytes = 43),
            input.copy(modifiedAt = null),
            input.copy(createdAt = null),
        )
        for (stale in staleInputs) assertFalse(f.repository.saveFileScore(original.copy(input = stale, value = 99.0)))
        f.repository.recordListing(f.root, DirectoryListing(emptyList(), listOf(
            DirectoryListing.Entry(input.path, input.sizeBytes, input.modifiedAt, input.createdAt),
        )), 999)
        assertTrue(f.repository.saveFileScore(original))
        f.repository.recordListing(f.root, DirectoryListing(emptyList(), listOf(
            DirectoryListing.Entry(input.path, 43, input.modifiedAt, input.createdAt),
        )), 1000)
        assertFalse(f.repository.saveFileScore(original.copy(value = 99.0)))
        f.repository.recordListing(f.root, DirectoryListing(emptyList(), emptyList()), 1001)
        assertFalse(f.repository.saveFileScore(original))
        assertEquals(listOf(original), f.repository.fileScores(listOf(input.path)))
        assertFalse(f.repository.saveFileScore(original.copy(input = input.copy(path = f.root / "missing"))))
    }

    @Test
    fun currentOnlyReadsValidateInventoryWithoutRemovingHistoricalAssessments() = concurrencyTest { f ->
        val original = seed(f)
        val paths = listOf(original.input.path)
        f.repository.saveFileScore(original)
        assertEquals(listOf(original), f.repository.fileScores(paths, currentOnly = true))
        assertEquals(listOf(original), f.repository.fileScores(paths, identity, currentOnly = true))
        f.repository.recordListing(f.root, DirectoryListing(emptyList(), listOf(
            DirectoryListing.Entry(original.input.path, 43, 123, 100),
        )), 999)
        assertTrue(f.repository.fileScores(paths, currentOnly = true).isEmpty())
        assertTrue(f.repository.fileScores(paths, identity, currentOnly = true).isEmpty())
        assertEquals(listOf(original), f.repository.fileScores(paths))
        f.repository.recordListing(f.root, DirectoryListing(emptyList(), listOf(
            DirectoryListing.Entry(original.input.path, 42, 123, 100),
        )), 1000)
        assertEquals(listOf(original), f.repository.fileScores(paths, identity, currentOnly = true))
        f.repository.recordListing(f.root, DirectoryListing(emptyList(), emptyList()), 1001)
        assertTrue(f.repository.fileScores(paths, identity, currentOnly = true).isEmpty())
        assertEquals(listOf(original), f.repository.fileScores(paths))
    }

    @Test
    fun typeAndVersionFiltersDoNotRequireAlgorithmOrConfigurationIdentity() = concurrencyTest { f ->
        val original = seed(f)
        val sameTypeAndVersion = listOf(original,
            original.copy(identity = identity.copy(algorithmId = "other")),
            original.copy(identity = identity.copy(configurationId = "custom")))
        val nextVersion = original.copy(identity = identity.copy(algorithmVersion = "2"))
        val otherType = original.copy(identity = identity.copy(type = "quality"))
        f.repository.saveFileScores(sameTypeAndVersion + nextVersion + otherType)
        val paths = listOf(original.input.path)
        assertEquals(sameTypeAndVersion.toSet(),
            f.repository.fileScores(paths, scoreType = "retention", algorithmVersion = "1").toSet())
        assertEquals((sameTypeAndVersion + nextVersion).toSet(),
            f.repository.fileScores(paths, scoreType = "retention").toSet())
        assertEquals((sameTypeAndVersion + otherType).toSet(),
            f.repository.fileScores(paths, algorithmVersion = "1").toSet())
        assertEquals(listOf(original),
            f.repository.fileScores(paths, identity, scoreType = "retention", algorithmVersion = "1"))
        assertFailsWith<IllegalArgumentException> { f.repository.fileScores(paths, identity, scoreType = "quality") }
        assertFailsWith<IllegalArgumentException> { f.repository.fileScores(paths, identity, algorithmVersion = "2") }
    }

    @Test
    fun batchSkipsStaleInputsAndCommitsAcceptedIdentitiesTogether() = concurrencyTest { f ->
        val original = seed(f)
        val other = original.copy(identity = identity.copy(type = "quality"))
        val stale = original.copy(input = original.input.copy(sizeBytes = 999), value = 99.0)
        val missing = original.copy(input = original.input.copy(path = f.root / "missing"))
        assertEquals(setOf(original.input.path), f.repository.saveFileScores(listOf(original, stale, missing, other)))
        assertEquals(setOf(original, other), f.repository.fileScores(listOf(original.input.path)).toSet())
        assertTrue(f.repository.saveFileScores(emptyList()).isEmpty())
    }

    @Test
    fun failureInLaterBatchScoreRollsBackEarlierScoreAndAllFactors() = concurrencyTest { f ->
        val original = seed(f)
        val other = original.copy(identity = identity.copy(type = "quality"))
        assertTrue(f.repository.saveFileScore(original))
        var inserts = 0
        f.driver.beforeStatement = {
            if (it.startsWith("INSERT INTO fileScoreFactor") && ++inserts == 4) error("later batch failure")
        }
        assertFailsWith<IllegalStateException> {
            f.repository.saveFileScores(listOf(original.copy(value = 99.0), other))
        }
        f.driver.beforeStatement = {}
        assertEquals(listOf(original), f.repository.fileScores(listOf(original.input.path)))
        assertNull(f.driver.currentTransaction())
    }

    @Test
    fun nonFiniteValuesAreRejectedWithoutOverwritingStoredScore() = concurrencyTest { f ->
        val original = seed(f)
        assertTrue(f.repository.saveFileScore(original))
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { f.repository.saveFileScore(original.copy(value = value)) }
            assertFailsWith<IllegalArgumentException> {
                f.repository.saveFileScore(original.copy(factors = listOf(ScoreFactor("invalid", value, "Invalid"))))
            }
        }
        assertEquals(listOf(original), f.repository.fileScores(listOf(original.input.path)))
    }

    @Test
    fun exceptionAfterScoreAndFactorMutationsRollsBackEverything() = concurrencyTest { f ->
        val original = seed(f)
        assertTrue(f.repository.saveFileScore(original))
        val start = f.driver.statements.size
        var inserts = 0
        f.driver.beforeStatement = {
            if (it.startsWith("INSERT INTO fileScoreFactor") && ++inserts == 2) error("factor failure")
        }
        assertFailsWith<IllegalStateException> {
            f.repository.saveFileScore(original.copy(value = 0.9, createdAt = 500))
        }
        f.driver.beforeStatement = {}
        assertTrue(f.driver.statements.drop(start).any { it.startsWith("DELETE FROM fileScoreFactor") })
        assertEquals(listOf(original), f.repository.fileScores(listOf(original.input.path)))
        assertNull(f.driver.currentTransaction())
    }

    @Test
    fun cancellationAfterActualFactorMutationRollsBackBeforeQueuedRead() = concurrencyTest { f ->
        val original = seed(f)
        assertTrue(f.repository.saveFileScore(original))
        f.driver.pauseAfter = { it.startsWith("INSERT INTO fileScoreFactor") }
        val save = launch { f.repository.saveFileScore(original.copy(value = 0.9, createdAt = 500)) }
        f.driver.entered.await()
        val count = f.driver.statements.size
        val read = async { f.repository.fileScores(listOf(original.input.path)) }
        runCurrent()
        assertFalse(read.isCompleted)
        assertEquals(count, f.driver.statements.size)
        save.cancelAndJoin()
        assertEquals(listOf(original), read.await())
        assertNull(f.driver.currentTransaction())
    }

    @Test
    fun largeReadsBatchScoresAndFactorsInsteadOfQueryingEveryFile() = concurrencyTest { f ->
        f.repository.seedRoot(f.root)
        val entries = (0 until 1100).map { DirectoryListing.Entry(f.root / "$it.jpg", it.toLong(), null) }
        f.repository.recordListing(f.root, DirectoryListing(emptyList(), entries), 200)
        val scores = f.repository.allFiles().map {
            FileScore(FileScoreInput.from(it), identity, 1.0, listOf(ScoreFactor("factor", 1.0, "Saved")), 300)
        }
        for (score in scores) assertTrue(f.repository.saveFileScore(score))
        for (filter in listOf(null, identity)) {
            val start = f.driver.statements.size
            assertEquals(scores.toSet(), f.repository.fileScores(scores.map { it.input.path }, filter).toSet())
            val reads = f.driver.statements.drop(start)
            assertTrue(reads.size <= 10, "Expected bounded batch queries, got ${reads.size}")
            assertTrue(reads.all { it.startsWith("SELECT") })
        }
    }
}
