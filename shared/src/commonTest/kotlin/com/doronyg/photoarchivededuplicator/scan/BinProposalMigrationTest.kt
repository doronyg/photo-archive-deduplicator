package com.doronyg.photoarchivededuplicator.scan

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.doronyg.photoarchivededuplicator.db.FilesDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BinProposalMigrationTest {
    @Test
    fun versionNineProposalsKeepTheirOriginalMappingAndEvidenceAfterUpgrade() = runTest {
        val historical = createMigrationTestDriver(migrate = false)
        try {
            for (statement in historicalVersionOneSchema) historical.execute(null, statement, 0).await()
            FilesDatabase.Schema.migrate(historical, 1, 9).await()
            historical.execute(null, """
                INSERT INTO fileBinRoot VALUES ('/Bin', 1, 123)
            """.trimIndent(), 0).await()
            historical.execute(null, """
                INSERT INTO fileOperationProposal VALUES
                ('old', 100, 200, '/original/archive', '/Bin', 'NEEDS_ATTENTION', 1, 1, 42,
                    '{"version":1,"destination":"/Bin/雪.jpg","evidence":"keep exactly"}')
            """.trimIndent(), 0).await()
            historical.execute(null, "PRAGMA user_version = 9", 0).await()
            val before = rows(historical, "SELECT * FROM fileOperationProposal", 10)
            val upgraded = createMigrationTestDriver(migrate = true)
            try {
                assertEquals(before, rows(upgraded, "SELECT * FROM fileOperationProposal", 10))
                assertEquals(listOf(listOf("/Bin", "1", "123")), rows(upgraded, "SELECT * FROM fileBinRoot", 3))
                upgraded.execute(null, """
                    INSERT INTO fileOperationProposal VALUES
                    ('new', 300, 400, NULL, '/Bin', 'READY', 1, 1, 42, '{}')
                """.trimIndent(), 0).await()
                assertEquals(listOf(listOf(null)), rows(upgraded,
                    "SELECT archiveRoot FROM fileOperationProposal WHERE id = 'new'", 1))
            } finally {
                upgraded.close()
            }
        } finally {
            historical.close()
        }
    }

    @Test
    fun releasedVersionEightPreservesAllHistoricalRowsAndStartsWithNoBinOrProposals() = runTest {
        val historical = createMigrationTestDriver(migrate = false)
        try {
            historical.execute(null, "PRAGMA foreign_keys = ON", 0).await()
            for (statement in historicalVersionOneSchema) historical.execute(null, statement, 0).await()
            FilesDatabase.Schema.migrate(historical, 1, 8).await()
            for (statement in fixture) historical.execute(null, statement, 0).await()
            historical.execute(null, "PRAGMA user_version = 8", 0).await()
            assertEquals(listOf(listOf("8")), rows(historical, "PRAGMA user_version", 1))
            val before = snapshot(historical)
            assertTrue(before.values.all { it.isNotEmpty() })
            assertTrue(rows(historical, "PRAGMA foreign_key_check", 4).isEmpty())

            // Reopening through the real driver runs the same automatic upgrade as desktop.
            val upgraded = createMigrationTestDriver(migrate = true)
            try {
                assertEquals(10L, FilesDatabase.Schema.version)
                assertEquals(listOf(listOf("10")), rows(upgraded, "PRAGMA user_version", 1))
                assertEquals(before, snapshot(upgraded))
                assertEquals(emptyList(), rows(upgraded, "SELECT * FROM fileBinRoot", 3))
                assertEquals(emptyList(), rows(upgraded, "SELECT * FROM fileOperationProposal", 10))
                assertEquals(rows(upgraded, "SELECT path FROM directoryExclusion ORDER BY path", 1),
                    rows(upgraded, "SELECT path FROM archiveExclusion ORDER BY path", 1))
                assertTrue(rows(upgraded, "PRAGMA foreign_key_check", 4).isEmpty())
                upgraded.execute(null, "PRAGMA foreign_keys = ON", 0).await()
                assertEquals(listOf(listOf("1")), rows(upgraded, "PRAGMA foreign_keys", 1))
            } finally {
                upgraded.close()
            }
        } finally {
            historical.close()
        }
    }

    private suspend fun snapshot(driver: SqlDriver): Map<String, List<List<String?>>> =
        mapOf("scanDirectory" to 7, "scanFile" to 8, "scanSession" to 4,
            "directoryExclusion" to 2, "uiPreference" to 2, "fileExif" to 18,
            "directoryAiClassification" to 8, "scoreProfile" to 5,
            "factorDefinition" to 3, "fileScore" to 9, "fileScoreFactor" to 4)
            .mapValues { (table, columns) -> rows(driver, "SELECT * FROM $table ORDER BY 1, 2", columns) }

    private suspend fun rows(driver: SqlDriver, sql: String, columns: Int): List<List<String?>> =
        driver.executeQuery(null, sql, { cursor ->
            val result = mutableListOf<List<String?>>()
            while (cursor.next().value) result += (0 until columns).map { cursor.getString(it) }
            QueryResult.Value(result.toList())
        }, 0).await()

    private val fixture = listOf(
        """INSERT INTO scanDirectory VALUES
            ('/archive', NULL, 'COMPLETED', 0, 1, 123, 789),
            ('/archive/pending', '/archive', 'PENDING', 2, 0, NULL, NULL)""",
        """INSERT INTO scanFile
            (path, directoryPath, name, extension, sizeBytes, modifiedAt, lastScanned, createdAt)
            VALUES ('/archive/photo.jpg', '/archive', 'photo.jpg', 'jpg', 42, NULL, 789, NULL)""",
        "INSERT INTO scanSession VALUES ('/archive', 12345, 1, 0), ('/finished', 67890, 0, 1)",
        "INSERT INTO directoryExclusion VALUES ('/archive/private', 'Keep 雪'), ('/other', NULL)",
        "INSERT INTO uiPreference VALUES ('scanRoot', '/saved'), ('future-setting', ' preserve me ')",
        """INSERT INTO fileExif
            (filePath, directoryPath, inputSizeBytes, inputModifiedAt, inputCreatedAt,
                status, capturedAt, extractedAt, readerId, readerVersion)
            VALUES ('/archive/photo.jpg', '/archive', 42, NULL, NULL,
                'EXTRACTED', '2018:08:26 12:00:00', 800, 'legacy-reader', 'old-version')""",
        """INSERT INTO directoryAiClassification VALUES
            ('/archive', 'http://test.invalid', '雪', '1', '', '/original', 'PROTECTED', 901)""",
        """INSERT INTO scoreProfile VALUES
            (17, 'retention', 'rules', 'old-version', 'default'),
            (29, ' retention 雪', 'rules', 'old-version', 'historical')""",
        """INSERT INTO factorDefinition VALUES
            (31, 'same', 'Historical 雪 explanation'), (43, 'same', 'Different explanation')""",
        """INSERT INTO fileScore VALUES
            (53, '/archive/photo.jpg', '/archive', 42, NULL, NULL, 17, -0.75, 902),
            (67, '/archive/missing.jpg', '/archive', 99, 123, 100, 29, 0.0, 903)""",
        """INSERT INTO fileScoreFactor VALUES
            (53, 2, 31, 0.5), (53, 9, 31, -0.25), (67, 4, 43, 0.0)""",
    )
}
