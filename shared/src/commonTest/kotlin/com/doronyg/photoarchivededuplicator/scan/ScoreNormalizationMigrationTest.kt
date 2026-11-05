package com.doronyg.photoarchivededuplicator.scan

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.doronyg.photoarchivededuplicator.db.FilesDatabase
import com.doronyg.photoarchivededuplicator.retention.FileScore
import com.doronyg.photoarchivededuplicator.retention.FileScoreInput
import com.doronyg.photoarchivededuplicator.retention.ScoreFactor
import com.doronyg.photoarchivededuplicator.retention.ScoreIdentity
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScoreNormalizationMigrationTest {
    @Test
    fun releasedVersionSevenNormalizesWithoutLosingHistoricalAssessmentsOrInventory() = runTest {
        val driver = createMigrationTestDriver(migrate = false)
        val io = TrackingIoDispatcher(StandardTestDispatcher(testScheduler))
        val guarded = IoGuardedDriver(driver, io)
        val scores = historicalScores()
        try {
            val before = withContext(io) {
                createReleasedVersionSeven(guarded)
                for (statement in inventoryFixture) guarded.execute(null, statement, 0).await()
                for (legacy in scores) insertLegacyScore(guarded, legacy)
                assertEquals(listOf(listOf("7")), rows(guarded, "PRAGMA user_version", 1))
                assertTrue(rows(guarded, "PRAGMA foreign_key_check", 4).isEmpty())
                Triple(
                    rows(guarded, "SELECT * FROM fileScore ORDER BY id", 12),
                    rows(guarded, "SELECT * FROM fileScoreFactor ORDER BY scoreId, ordinal", 5),
                    inventorySnapshot(guarded),
                )
            }
            val upgraded = withContext(io) { createMigrationTestDriver(migrate = true) }
            try {
                val upgradedGuarded = IoGuardedDriver(upgraded, io)
                withContext(io) {
                    upgradedGuarded.execute(null, "PRAGMA foreign_keys = ON", 0).await()
                    assertEquals(8L, FilesDatabase.Schema.version)
                    assertEquals(listOf(listOf("8")), rows(upgradedGuarded, "PRAGMA user_version", 1))
                    assertEquals(before.first, rows(upgradedGuarded, """
                        SELECT s.id, s.filePath, s.directoryPath, s.inputSizeBytes, s.inputModifiedAt,
                            s.inputCreatedAt, p.scoreType, p.algorithmId, p.algorithmVersion,
                            p.configurationId, s.value, s.createdAt
                        FROM fileScore s JOIN scoreProfile p ON p.id = s.profileId ORDER BY s.id
                    """.trimIndent(), 12))
                    assertEquals(before.second, rows(upgradedGuarded, """
                        SELECT f.scoreId, f.ordinal, d.factorKey, f.contribution, d.explanation
                        FROM fileScoreFactor f JOIN factorDefinition d ON d.id = f.definitionId
                        ORDER BY f.scoreId, f.ordinal
                    """.trimIndent(), 5))
                    assertEquals(before.third, inventorySnapshot(upgradedGuarded))
                    val profiles = scores.map { it.score.identity }.distinct()
                    val definitions = scores.flatMap { it.score.factors }
                        .map { it.key to it.explanation }.distinct()
                    assertEquals(profiles.size.toString(),
                        rows(upgradedGuarded, "SELECT COUNT(*) FROM scoreProfile", 1).single().single())
                    assertEquals(definitions.size.toString(),
                        rows(upgradedGuarded, "SELECT COUNT(*) FROM factorDefinition", 1).single().single())
                    assertTrue(rows(upgradedGuarded, """
                        SELECT filePath, profileId FROM fileScore
                        GROUP BY filePath, profileId HAVING COUNT(*) > 1
                    """.trimIndent(), 2).isEmpty())
                    assertEquals(listOf(listOf("1")), rows(upgradedGuarded, "PRAGMA foreign_keys", 1))
                    assertTrue(rows(upgradedGuarded, "PRAGMA foreign_key_check", 4).isEmpty())
                    assertEquals(setOf("scoreProfile"),
                        rows(upgradedGuarded, "PRAGMA foreign_key_list(fileScore)", 8).map { it[2] }.toSet())
                    assertEquals(setOf("fileScore", "factorDefinition"),
                        rows(upgradedGuarded, "PRAGMA foreign_key_list(fileScoreFactor)", 8).map { it[2] }.toSet())
                }
                val repository = DirectoryRepository(createFilesDatabase(upgradedGuarded), io)
                val paths = scores.map { it.score.input.path }.distinct()
                assertEquals(scores.map { it.score }, repository.fileScores(paths))
                for (identity in scores.map { it.score.identity }.distinct()) {
                    assertEquals(scores.map { it.score }.filter { it.identity == identity },
                        repository.fileScores(paths, identity))
                }
                // Both stale fingerprints and files no longer present remain historical rows.
                assertEquals(scores.map { it.score }.filter {
                    it.input.path == "/archive/photo.jpg".toPath() && it.input.sizeBytes == 42L
                }, repository.fileScores(paths, currentOnly = true))
            } finally {
                withContext(io) { upgraded.close() }
            }
        } finally {
            withContext(io) { driver.close() }
        }
    }

    @Test
    fun emptyReleasedVersionSevenUpgradesWithForeignKeysEnabled() = runTest {
        val driver = createMigrationTestDriver(migrate = false)
        try {
            createReleasedVersionSeven(driver)
            FilesDatabase.Schema.migrate(driver, 7, 8).await()
            assertEquals(8L, FilesDatabase.Schema.version)
            for (table in listOf("scoreProfile", "factorDefinition", "fileScore", "fileScoreFactor")) {
                assertEquals(listOf(listOf("0")), rows(driver, "SELECT COUNT(*) FROM $table", 1))
            }
            assertEquals(listOf(listOf("1")), rows(driver, "PRAGMA foreign_keys", 1))
            assertTrue(rows(driver, "PRAGMA foreign_key_check", 4).isEmpty())
        } finally {
            driver.close()
        }
    }

    private suspend fun createReleasedVersionSeven(driver: SqlDriver) {
        driver.execute(null, "PRAGMA foreign_keys = ON", 0).await()
        for (statement in historicalVersionOneSchema) driver.execute(null, statement, 0).await()
        FilesDatabase.Schema.migrate(driver, 1, 7).await()
        driver.execute(null, "PRAGMA user_version = 7", 0).await()
    }

    private suspend fun inventorySnapshot(driver: SqlDriver): Map<String, List<List<String?>>> =
        mapOf("scanDirectory" to 7, "scanFile" to 8, "directoryExclusion" to 2,
            "scanSession" to 4, "uiPreference" to 2, "fileExif" to 18,
            "directoryAiClassification" to 8).mapValues { (table, columns) ->
            rows(driver, "SELECT * FROM $table ORDER BY 1, 2", columns)
        }

    private suspend fun rows(driver: SqlDriver, sql: String, columns: Int): List<List<String?>> =
        driver.executeQuery(null, sql, { cursor ->
            val result = mutableListOf<List<String?>>()
            while (cursor.next().value) result += (0 until columns).map { cursor.getString(it) }
            QueryResult.Value(result.toList())
        }, 0).await()

    private data class LegacyScore(val id: Long, val score: FileScore, val ordinals: List<Int>)

    private fun historicalScores(): List<LegacyScore> {
        val base = ScoreIdentity("retention", "rules", "1", "default")
        val identities = listOf(
            base,
            base.copy(type = "Retention"),
            base.copy(algorithmId = "Rules"),
            base.copy(algorithmVersion = "1 "),
            base.copy(configurationId = "Default"),
            ScoreIdentity(" retention\t雪", "rules'; --", "版本\n2", " default ' \" ;\n"),
            base.copy(type = ""),
        )
        val duplicateFactors = listOf(
            ScoreFactor("same", 0.5, "Preserved\n雪 ' \" ; --"),
            ScoreFactor("same", -0.25, "Preserved\n雪 ' \" ; --"),
            ScoreFactor("same", 0.0, "Different explanation"),
            ScoreFactor("Same", 1.25, "Preserved\n雪 ' \" ; --"),
            ScoreFactor(" same\t雪", -2.0, ""),
        )
        val input = FileScoreInput("/archive/photo.jpg".toPath(), "/archive".toPath(), 42, null, null)
        return identities.mapIndexed { index, identity ->
            val factors = if (index == 1) emptyList() else duplicateFactors
            LegacyScore(17L + index * 13, FileScore(input, identity,
                if (index % 2 == 0) -0.75 else 0.0, factors, 900L + index),
                factors.indices.map { it * 3 })
        } + listOf(
            LegacyScore(401, FileScore(input.copy(path = "/archive/missing ' 雪.jpg".toPath(),
                modifiedAt = 123, createdAt = 100), base, -9.0,
                duplicateFactors.take(2), 1), listOf(2, 9)),
            LegacyScore(809, FileScore(input.copy(sizeBytes = 99), base.copy(configurationId = "stale"),
                0.0, emptyList(), 2), emptyList()),
        )
    }

    private suspend fun insertLegacyScore(driver: SqlDriver, legacy: LegacyScore) {
        val score = legacy.score
        val input = score.input
        val identity = score.identity
        driver.execute(null, """
            INSERT INTO fileScore VALUES (
                ${legacy.id}, ${quote(input.path.toString())}, ${quote(input.directoryPath.toString())},
                ${input.sizeBytes}, ${input.modifiedAt ?: "NULL"}, ${input.createdAt ?: "NULL"},
                ${quote(identity.type)}, ${quote(identity.algorithmId)}, ${quote(identity.algorithmVersion)},
                ${quote(identity.configurationId)}, ${score.value}, ${score.createdAt}
            )
        """.trimIndent(), 0).await()
        score.factors.forEachIndexed { index, factor ->
            driver.execute(null, """
                INSERT INTO fileScoreFactor VALUES (${legacy.id}, ${legacy.ordinals[index]},
                    ${quote(factor.key)}, ${factor.contribution}, ${quote(factor.explanation)})
            """.trimIndent(), 0).await()
        }
    }

    private fun quote(value: String): String = "'${value.replace("'", "''")}'"

    private val inventoryFixture = listOf(
        """INSERT INTO scanDirectory VALUES
            ('/archive', NULL, 'COMPLETED', 0, 1, 123, 789),
            ('/archive/retry', '/archive', 'PENDING', 2, 0, NULL, NULL)""",
        """INSERT INTO scanFile (path, directoryPath, name, extension, sizeBytes, modifiedAt, lastScanned, createdAt)
            VALUES ('/archive/photo.jpg', '/archive', 'photo.jpg', 'jpg', 42, NULL, 789, NULL)""",
        "INSERT INTO directoryExclusion VALUES ('/archive/private', 'Keep 雪'), ('/other', NULL)",
        "INSERT INTO scanSession VALUES ('/archive', 12345, 1, 0), ('/finished', 67890, 0, 1)",
        "INSERT INTO uiPreference VALUES ('scanRoot', '/saved'), ('future-setting', ' preserve me ')",
        """INSERT INTO fileExif (
            filePath, directoryPath, inputSizeBytes, inputModifiedAt, inputCreatedAt,
            status, extractedAt, readerId, readerVersion
        ) VALUES ('/archive/photo.jpg', '/archive', 42, NULL, NULL, 'NO_EXIF', 800, 'old', '1')""",
        """INSERT INTO directoryAiClassification VALUES
            ('/archive', 'http://test.invalid', '雪', '1', '', '/original', 'UNKNOWN', 901)""",
    )
}
