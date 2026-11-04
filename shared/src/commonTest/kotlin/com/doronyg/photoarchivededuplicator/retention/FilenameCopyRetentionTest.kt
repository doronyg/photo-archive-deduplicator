package com.doronyg.photoarchivededuplicator.retention

import com.doronyg.photoarchivededuplicator.exif.ExifInput
import com.doronyg.photoarchivededuplicator.exif.ExifMetadata
import com.doronyg.photoarchivededuplicator.exif.ExifReadResult
import com.doronyg.photoarchivededuplicator.exif.ExifRecord
import com.doronyg.photoarchivededuplicator.exif.ExifStatus
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FilenameCopyRetentionTest {
    private val scorer = RetentionScorer()
    private fun score(name: String, folder: String = "/Pictures/cell pics", capture: String = "2010:07:12 13:20:54"): FileScore {
        val directory = folder.toPath()
        val file = FileRecord(directory / name, directory, name, "jpg", 100_863, null, 1)
        val exif = ExifRecord(ExifInput.from(file), ExifReadResult(ExifStatus.EXTRACTED, ExifMetadata(capturedAt = capture)), 1)
        return scorer.score(file, 2, exif)
    }

    @Test
    fun plainNamesBeatBackupTimestampCopiesWithoutInventingFolderOrAgePreferences() {
        assertEquals(0.0, score("תמונה0006.jpg").value)
        assertEquals(-20.0, score("תמונה0006 (2020_09_13 05_22_30 UTC).jpg").value)
        assertEquals(-20.0, score("תמונה0006 (2021_12_30 01_53_40 UTC).JPG").value)
        assertEquals(0.0, score("תמונה0006.jpg", "/Pictures/foreign picturea").value)
        assertEquals(0.0, score("תמונה0006.jpg", "/Pictures/foreign pictures").value)
    }

    @Test
    fun datedSonyExampleKeepsDirectoryExifEvidenceDominantOverCopyNames() {
        val folder = "/Pictures/2019-07-16"
        val capture = "2019:07:16 11:16:19"
        val expected = mapOf(
            "DSC_4061.jpg" to 100.0,
            "DSC_4061 (2020_09_13 05_22_30 UTC).jpg" to 80.0,
            "DSC_4061 (2021_12_30 01_53_40 UTC).JPG" to 80.0,
            "DSC_4061-001.jpg" to 90.0,
            "DSC_4061-001 (2020_09_13 05_22_30 UTC).jpg" to 70.0,
            "DSC_4061-001 (2021_12_30 01_53_40 UTC).jpg" to 70.0,
        )
        expected.forEach { (name, value) -> assertEquals(value, score(name, folder, capture).value, name) }
        assertEquals(-35.0, score("DSC_4061.JPG", "/Pictures/Google Photos/.picasaoriginals", capture).value)
        assertEquals(60.0, score("DSC_4061.jpg", "/Pictures/2019-07-17", capture).value)
    }

    @Test
    fun suffixRecognitionIsStrictAndDoesNotPenalizeOrdinaryCameraCountersOrInvalidDates() {
        for (name in listOf(
            "DSC_4061.jpg", "IMG_0001.jpg", "DSC_4061-000.jpg", "DSC_4061-01.jpg",
            "DSC_4061-001-edit.jpg", "photo (2023_02_29 12_00_00 UTC).jpg",
            "photo (2024_01_01 25_00_00 UTC).jpg", "photo (2024_01_01 12_00_00).jpg",
            "photo (2024_01_01 12_00_00 UTC) edited.jpg",
        )) assertEquals(0.0, score(name).value, name)
        assertEquals(-10.0, score("DSC_4061-999.jpg").value)
        val repeated = score("photo-001 (2020_09_13 05_22_30 UTC) (2021_12_30 01_53_40 UTC).jpg")
        assertEquals(-30.0, repeated.value)
        assertEquals(1, repeated.factors.count { it.key == "backup-filename" })
        assertEquals(1, repeated.factors.count { it.key == "numbered-filename" })
    }

    @Test
    fun copyDecorationDoesNotHideOriginalFilenameDateProtectionOrBecomeCaptureDateEvidence() {
        val protected = score("20190716_111619-001 (2020_09_13 05_22_30 UTC).jpg",
            "/Pictures/2019-07-16", "2019:07:17 11:16:19")
        assertEquals(70.0, protected.value)
        assertEquals(0.0, protected.factors.single { it.key == "exif-date-mismatch" }.contribution)
        assertEquals(-20.0, score("photo (2020_09_13 05_22_30 UTC).jpg", "/Pictures/plain").value)
    }

    @Test
    fun repeatedMixedCopySuffixesAreRecognizedInEitherOrderAndCountEachCueOnce() {
        for (name in listOf(
            "photo (2020_09_13 05_22_30 UTC)-001.jpg",
            "photo-001-002 (2020_09_13 05_22_30 UTC)-003.jpg",
            "photo (2020_09_13 05_22_30 UTC)-001 (2021_12_30 01_53_40 UTC).jpg",
        )) {
            val result = score(name)
            assertEquals(-30.0, result.value, name)
            assertEquals(1, result.factors.count { it.key == "backup-filename" }, name)
            assertEquals(1, result.factors.count { it.key == "numbered-filename" }, name)
        }
        assertEquals(70.0, score("20190716_111619 (2020_09_13 05_22_30 UTC)-001.jpg",
            "/Pictures/2019-07-16", "2019:07:17 11:16:19").value)
    }

    @Test
    fun filenameWeightsAreValidatedConfigurableAndPartOfTheCachedProfile() {
        val defaults = RetentionConfig()
        assertEquals(-20.0, defaults.backupFilenamePenalty)
        assertEquals(-10.0, defaults.numberedFilenamePenalty)
        assertNotEquals(scorer.identity, RetentionScorer(defaults.copy(backupFilenamePenalty = -21.0)).identity)
        assertNotEquals(scorer.identity, RetentionScorer(defaults.copy(numberedFilenamePenalty = -11.0)).identity)
        assertEquals("6", scorer.identity.algorithmVersion)
        assertFailsWith<IllegalArgumentException> { RetentionConfig(backupFilenamePenalty = 1.0) }
        assertFailsWith<IllegalArgumentException> { RetentionConfig(numberedFilenamePenalty = Double.NaN) }
        assertTrue(score("photo-001.jpg").factors.single { it.key == "numbered-filename" }.explanation.contains("not proof"))
    }
}
