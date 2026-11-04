package com.doronyg.photoarchivededuplicator.retention

import com.doronyg.photoarchivededuplicator.scan.FileRecord
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RetentionEvidenceTest {
    private val scorer = RetentionScorer()

    private fun file(folder: String): FileRecord {
        val directory = folder.toPath()
        return FileRecord(directory / "Photo.JPG", directory, "Photo.JPG", "jpg", 100, null, 1)
    }

    @Test
    fun filenameEvidencePreservesCaseAndStripsRepeatedMixedSuffixes() {
        assertEquals(FilenameEvidence("Photo.JPG", false, null), filenameEvidence("Photo.JPG"))
        for (name in listOf(
            "Photo-001 (2020_09_13 05_22_30 UTC).JPG",
            "Photo (2020_09_13 05_22_30 utc)-001.JPG",
            "Photo-002 (2020_09_13 05_22_30 UTC)-001 (2024_02_29 23_59_59 UTC).JPG",
        )) {
            assertEquals(FilenameEvidence("Photo.JPG", true, "-001"), filenameEvidence(name), name)
        }
        assertEquals(FilenameEvidence("Photo", false, "-999"), filenameEvidence("Photo-999"))
        assertEquals(FilenameEvidence(".Photo", false, "-001"), filenameEvidence(".Photo-001"))
    }

    @Test
    fun invalidSuffixesRemainUntouchedAndStopParsingEarlierSuffixes() {
        for (name in listOf(
            "Photo-000.jpg", "Photo-01.jpg", "Photo-1000.jpg", "Photo-001-edit.jpg",
            "Photo (2023_02_29 12_00_00 UTC).jpg",
            "Photo (2024_01_01 25_00_00 UTC).jpg",
            "Photo (0000_01_01 12_00_00 UTC).jpg",
            "Photo (2024_01_01 12_00_00).jpg",
            "Photo-001 (2023_02_29 12_00_00 UTC).jpg",
            "Photo (2024_01_01 12_00_00 UTC) edited.jpg",
        )) assertEquals(FilenameEvidence(name, false, null), filenameEvidence(name), name)
        assertEquals(
            FilenameEvidence("Photo-000.jpg", true, null),
            filenameEvidence("Photo-000 (2024_01_01 12_00_00 UTC).jpg"),
        )
    }

    @Test
    fun directoryEvidenceReusesAllCompleteDateFormatsAndValidation() {
        for (folder in listOf(
            "/Photos/2019-07-16", "/Photos/2019_07_16",
            "/Photos/20190716", "/Photos/2019/07/16",
        )) {
            val evidence = scorer.directoryEvidence(file(folder))
            assertEquals(DirectoryRetentionEvidence("2019-07-16", true, RetentionLocation.ORDINARY,
                "/Photos".toPath()), evidence, folder)
            assertTrue(scorer.hasCompleteDirectoryDate(file(folder)), folder)
        }
        for (folder in listOf("/Photos/2023-02-29", "/Photos/2024/08", "/Photos/2024", "/Photos/Zoo")) {
            assertEquals(DirectoryRetentionEvidence(null, false, RetentionLocation.ORDINARY),
                scorer.directoryEvidence(file(folder)), folder)
            assertFalse(scorer.hasCompleteDirectoryDate(file(folder)), folder)
        }
    }

    @Test
    fun dateAncestorsDoNotMakeMeaningfulChildCollectionsDirectlyDated() {
        for (folder in listOf("/Photos/2019-07-16 Zoo", "/Photos/2019-07-16_Zoo", "/Photos/2019-07-16-Zoo")) {
            val labeled = file(folder)
            assertEquals(DirectoryRetentionEvidence("2019-07-16", false, RetentionLocation.ORDINARY),
                scorer.directoryEvidence(labeled), folder)
            assertEquals(100.0, scorer.score(labeled, 1).value, folder)
            assertTrue(scorer.hasCompleteDirectoryDate(labeled), folder)
        }
        val child = file("/Photos/2019-07-16/Zoo")
        assertEquals(DirectoryRetentionEvidence("2019-07-16", false, RetentionLocation.ORDINARY),
            scorer.directoryEvidence(child))
        assertTrue(scorer.hasCompleteDirectoryDate(child))
        assertEquals("2019-07-16",
            scorer.directoryEvidence(file("/Photos/2019-07-16/20200717")).date)
        assertFalse(scorer.directoryEvidence(file("/Photos/2019-07-16/20200717")).directlyDated)
        assertEquals(DirectoryRetentionEvidence("2020-07-17", true, RetentionLocation.ORDINARY,
            "/Photos/2019-07-16".toPath()),
            scorer.directoryEvidence(file("/Photos/2019-07-16/2020-07-17")))
        assertEquals(DirectoryRetentionEvidence("2020-07-17", true, RetentionLocation.ORDINARY,
            "/Photos/20190716".toPath()),
            scorer.directoryEvidence(file("/Photos/20190716/2020/07/17")))
    }

    @Test
    fun organizationRootsPreserveMeaningfulParentsAndOnlyRemoveSelectedDateComponents() {
        for (parent in listOf("/Pictures", "/Pictures/Family", "/Pictures/Zoo", "/Pictures/2020/Family")) {
            for (date in listOf("2023-02-18", "2023_02_18", "20230218", "2023/02/18")) {
                assertEquals(parent.toPath(),
                    scorer.directoryEvidence(file("$parent/$date")).organizationRoot, "$parent/$date")
            }
        }
        for (folder in listOf(
            "/Pictures/2023", "/Pictures/2023/02", "/Pictures/2023-02-18 Zoo",
            "/Pictures/2023-02-18/Zoo", "/Pictures/2023/02/18/Zoo",
        )) assertEquals(null, scorer.directoryEvidence(file(folder)).organizationRoot, folder)
    }

    @Test
    fun directoryLocationsMatchConfiguredComponentsWordsAndPrefixesWithTemporaryPrecedence() {
        for (folder in listOf("/Photos/BACKUP/Zoo", "/Photos/WhatsApp Images", "/Photos/Google Photos")) {
            assertEquals(RetentionLocation.BACKUP, scorer.directoryEvidence(file(folder)).location, folder)
        }
        for (folder in listOf("/Photos/Backup/TMP", "/Photos/.picasa3temp_123/Zoo")) {
            assertEquals(RetentionLocation.TEMPORARY, scorer.directoryEvidence(file(folder)).location, folder)
        }
        for (folder in listOf("/Photos/Backup Zoo", "/Photos/MyWhatsAppImages", "/Photos/Template")) {
            assertEquals(RetentionLocation.ORDINARY, scorer.directoryEvidence(file(folder)).location, folder)
        }
        val configured = RetentionScorer(RetentionConfig(
            backupDirectoryNames = setOf("Archive"),
            backupDirectoryWords = setOf("Copy"),
            temporaryDirectoryNames = setOf("Scratch"),
            temporaryDirectoryPrefixes = setOf("Staging_"),
        ))
        for (folder in listOf("/ARCHIVE/Zoo", "/Photos/Copy Images")) {
            assertEquals(RetentionLocation.BACKUP, configured.directoryEvidence(file(folder)).location, folder)
        }
        for (folder in listOf("/archive/SCRATCH", "/Photos/STAGING_123")) {
            assertEquals(RetentionLocation.TEMPORARY, configured.directoryEvidence(file(folder)).location, folder)
        }
        assertEquals(RetentionLocation.ORDINARY, configured.directoryEvidence(file("/Backup/Temp")).location)
    }
}
