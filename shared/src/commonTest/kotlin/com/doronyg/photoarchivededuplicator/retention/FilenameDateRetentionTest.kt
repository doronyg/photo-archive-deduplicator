package com.doronyg.photoarchivededuplicator.retention

import com.doronyg.photoarchivededuplicator.scan.FileRecord
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class FilenameDateRetentionTest {
    private val scorer = RetentionScorer()
    private val filename = "IMG-20150723-WA0003.jpg"

    private fun score(folder: String, name: String = filename, modified: Long? = null, created: Long? = null): FileScore {
        val directory = "/Pictures/$folder".toPath()
        return scorer.score(FileRecord(directory / name, directory, name, "jpg", 299417, modified, 1, created), 100)
    }

    @Test
    fun reportedCopiesScoreTwentyTwentyAndMinusTwentyFiveWithoutMisleadingTimestampBonus() {
        val first = score("2016-06-08", modified = Instant.parse("2016-06-08T04:06:47Z").toEpochMilliseconds(),
            created = Instant.parse("2023-12-01T02:30:22Z").toEpochMilliseconds())
        val second = score("2016-12-17-Whats Up",
            modified = Instant.parse("2016-12-18T03:42:56Z").toEpochMilliseconds())
        val third = score("PhotoSync/Samsung SM-A705MN/WhatsApp Images")
        assertEquals(listOf(20.0, 20.0, -25.0), listOf(first.value, second.value, third.value))
        assertEquals(listOf(100.0, -80.0), first.factors.map { it.contribution })
        val mismatch = first.factors.single { it.key == "filename-date-mismatch" }
        assertTrue("2015-07-23" in mismatch.explanation && "2016-06-08" in mismatch.explanation)
        assertFalse(first.factors.any { it.key.endsWith("-date-support") })
    }

    @Test
    fun filenameAloneNeverCreatesDateMismatchPenalty() {
        for ((folder, expected) in listOf(
            "Zoo" to 0.0, "picturesToPrint" to 0.0, "2016" to 10.0,
            "2016/06" to 30.0, "Google Photos" to -25.0, "Temp" to -60.0,
        )) {
            val result = score(folder)
            assertEquals(expected, result.value, folder)
            assertFalse(result.factors.any { it.key == "filename-date-mismatch" }, folder)
        }
    }

    @Test
    fun matchingFilenameDatePreservesDirectoryAndWeakTimestampBonuses() {
        val timestamp = Instant.parse("2015-07-23T12:00:00Z").toEpochMilliseconds()
        assertEquals(103.0, score("2015-07-23", modified = timestamp, created = timestamp).value)
        assertEquals(103.0, score("2015-07-23-Trip", modified = timestamp, created = timestamp).value)
        val wrongFolderTimestamp = Instant.parse("2016-06-08T12:00:00Z").toEpochMilliseconds()
        assertEquals(20.0, score("2016-06-08", modified = wrongFolderTimestamp, created = wrongFolderTimestamp).value)
    }

    @Test
    fun filenameDatesMustHaveRecognizedStructureAndBeCalendarValid() {
        for (name in listOf(
            "IMG-20150230-WA0003.jpg", "IMG-20230229-WA0003.jpg", "IMG-00000101-WA0003.jpg",
            "receipt20150723.jpg", "IMG-20150723-other.jpg", "IMG-20150723-WA0003.jpg.bak", "photo.jpg",
        )) assertEquals(100.0, score("2016-06-08", name).value, name)
        for (name in listOf(filename, "img-20150723-wa0003.JPG", "VID-20150723-WA0003.mp4",
            "IMG-20240229-WA0003.jpg")) {
            assertEquals(20.0, score("2016-06-08", name).value, name)
            assertEquals(0.0, score("Zoo", name).value, name)
        }
    }

    @Test
    fun descriptiveDateFoldersRequireLeadingValidDateAndLabelSeparator() {
        for (folder in listOf("2016-12-17-Whats Up", "2016-12-17 Holiday", "2016-12-17_Album")) {
            assertEquals(20.0, score(folder).value, folder)
        }
        for (folder in listOf("prefix2016-12-17-Album", "2016-12-17suffix", "2016-02-30-Album")) {
            assertEquals(0.0, score(folder).value, folder)
        }
        for (folder in listOf("2016_06_08", "20160608", "2016/06/08")) {
            val result = score(folder)
            assertEquals(-40.0, result.value, folder)
            assertEquals(-80.0, result.factors.single { it.key == "filename-date-mismatch" }.contribution)
        }
    }

    @Test
    fun whatsappWordsAndPhotoSyncAreCaseInsensitiveLocationEvidenceCountedOnce() {
        for (folder in listOf("WhatsApp", "WHATSAPP IMAGES", "WhatsApp Video", "Archive WhatsApp export",
            "WhatsApp_backup", "PHOTOSYNC", "PhotoSync/device/WhatsApp Images/Google Photos")) {
            assertEquals(-25.0, score(folder).value, folder)
        }
        for (folder in listOf("WhatsApplication", "NotWhatsApp", "WhatsAppish", "PhotoSync-copy")) {
            assertEquals(0.0, score(folder).value, folder)
        }
        assertEquals(-60.0, score("Temp/WhatsApp Images/PhotoSync").value)
        assertEquals(75.0, score("2015-07-23/WhatsApp Images").value)
    }

    @Test
    fun mismatchWeightIsConfigurableAndWindowsPathsUseTheirActualFilename() {
        val directory = "C:\\Pictures\\2016-06-08".toPath()
        val file = FileRecord(directory / filename, directory, filename, "jpg", 12, null, 1)
        assertEquals(20.0, scorer.score(file, 1).value)
        val custom = RetentionScorer(RetentionConfig(filenameDateMismatchPenalty = -70.0))
        assertEquals(30.0, custom.score(file, 1).value)
        assertFalse(scorer.identity == custom.identity)
        val unknownDirectory = "/Pictures/WhatsApp Images".toPath()
        val withoutWhatsAppRule = RetentionScorer(RetentionConfig(backupDirectoryWords = emptySet()))
        assertEquals(0.0, withoutWhatsAppRule.score(
            file.copy(path = unknownDirectory / filename, directoryPath = unknownDirectory), 1,
        ).value)
    }
}
