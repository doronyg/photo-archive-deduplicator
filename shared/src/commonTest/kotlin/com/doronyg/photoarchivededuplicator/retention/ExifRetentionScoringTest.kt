package com.doronyg.photoarchivededuplicator.retention

import com.doronyg.photoarchivededuplicator.exif.ExifInput
import com.doronyg.photoarchivededuplicator.exif.ExifMetadata
import com.doronyg.photoarchivededuplicator.exif.ExifReadResult
import com.doronyg.photoarchivededuplicator.exif.ExifRecord
import com.doronyg.photoarchivededuplicator.exif.ExifStatus
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import com.doronyg.photoarchivededuplicator.scan.DuplicateCandidateGroup
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class ExifRetentionScoringTest {
    private val scorer = RetentionScorer()

    private fun file(folder: String, name: String = "DSC00413.ARW", timestamps: Boolean = false): FileRecord {
        val directory = "/Pictures/$folder".toPath()
        val timestamp = if (timestamps) Instant.parse("2013-06-25T12:00:00Z").toEpochMilliseconds() else null
        return FileRecord(directory / name, directory, name, "arw", 42, timestamp, 1, timestamp)
    }

    private fun exif(
        file: FileRecord,
        capturedAt: String? = "2013:06:25 17:43:36",
        offset: String? = null,
        status: ExifStatus = ExifStatus.EXTRACTED,
    ) = ExifRecord(ExifInput.from(file), ExifReadResult(status, ExifMetadata(capturedAt, offset)), 2)

    @Test
    fun matchingExifMakesAlternativeCompleteDatesWorthNinetyWithoutRaisingPreferredOrganization() {
        for (folder in listOf("2013_06_25", "20130625", "2013/06/25")) {
            val file = file(folder)
            val result = scorer.score(file, 3, exif(file))
            assertEquals(90.0, result.value, folder)
            assertEquals(50.0, result.factors.single { it.key == "exif-date-match" }.contribution)
            assertTrue(result.factors.any { "2013-06-25" in it.explanation })
            assertEquals(result.value, result.factors.sumOf { it.contribution })
        }
        val preferred = file("2013-06-25")
        assertEquals(100.0, scorer.score(preferred, 3, exif(preferred)).value)
        val supported = file("2013_06_25", timestamps = true)
        assertEquals(93.0, scorer.score(supported, 3, exif(supported)).value)
    }

    @Test
    fun reportedSonyExampleHasARealMismatchRatherThanReceivingAMatchBonus() {
        val file = file("2013_06_25")
        val result = scorer.score(file, 3, exif(file, "2013:06:30 17:43:36"))
        assertEquals(0.0, result.value)
        val mismatch = result.factors.single { it.key == "exif-date-mismatch" }
        assertEquals(-40.0, mismatch.contribution)
        assertTrue("2013-06-30" in mismatch.explanation && "2013-06-25" in mismatch.explanation)
        assertFalse(result.factors.any { it.key == "exif-date-match" })
        val preferred = file("2013-06-25", timestamps = true)
        val contradicted = scorer.score(preferred, 3, exif(preferred, "2013:06:30 17:43:36"))
        assertEquals(60.0, contradicted.value)
        assertFalse(contradicted.factors.any { it.key.endsWith("-date-support") })
    }

    @Test
    fun matchingFilenameProtectsOrganizationAndTimestampPointsFromConflictingExif() {
        for (folder in listOf("2013-06-25", "2013_06_25")) {
            for (name in listOf("IMG-20130625-WA0003.jpg", "20130625_174336.jpg", "IMG_20130625.jpg",
                "IMG_20130625_174336.JPG", "PXL_20130625_174336123.jpg", "2013-06-25.jpg", "2013_06_25.jpg")) {
                val file = file(folder, name, timestamps = true)
                val before = scorer.score(file, 3)
                val after = scorer.score(file, 3, exif(file, "2013:06:30 17:43:36"))
                assertEquals(before.value, after.value, "$folder/$name")
                assertEquals(0.0, after.factors.single { it.key == "exif-date-mismatch" }.contribution)
                assertTrue(after.factors.any { "filename" in it.explanation && "no penalty" in it.explanation })
            }
        }
    }

    @Test
    fun filenameProtectionRequiresARecognizedValidDateInTheActualPathName() {
        for (name in listOf("receipt20130625.jpg", "IMG_20130230.jpg", "DSC00413.ARW", "IMG_20130624.jpg")) {
            val file = file("2013_06_25", name)
            assertEquals(0.0, scorer.score(file, 3, exif(file, "2013:06:30 17:43:36")).value, name)
        }
        val inconsistentName = file("2013_06_25").copy(name = "IMG_20130625.jpg")
        assertEquals(0.0, scorer.score(inconsistentName, 3, exif(inconsistentName, "2013:06:30 17:43:36")).value)
    }

    @Test
    fun exifAndFilenameDisagreementWithFolderNeverDoubleCountsDatePenalties() {
        val file = file("2013_06_25", "IMG-20130624-WA0003.jpg")
        val result = scorer.score(file, 3, exif(file, "2013:06:30 17:43:36"))
        assertEquals(0.0, result.value)
        assertEquals(-40.0, result.factors.filter { it.key.endsWith("date-mismatch") }.sumOf { it.contribution })
        val matchingExif = scorer.score(file, 3, exif(file))
        assertEquals(90.0, matchingExif.value, "Matching original capture evidence supports the folder despite an advisory filename")
    }

    @Test
    fun comparisonsUseOriginalCalendarDateNotUtcOrSystemTimezone() {
        val file = file("2024_02_29")
        for (offset in listOf(null, "-08:00", "+14:00")) {
            val record = exif(file, "2024:02:29 23:30:00", offset)
            assertEquals(90.0, scorer.score(file, 3, record).value, offset)
        }
    }

    @Test
    fun missingInvalidSuspiciousFailedAndStaleExifAreNeutral() {
        val file = file("2013_06_25")
        for (date in listOf(null, "", "1969:12:31 23:59:59", "1970:01:01 00:00:00",
            "0000:00:00 00:00:00", "2100:01:01 00:00:00", "2013:02:29 12:00:00",
            "2013:06:25 24:00:00", "2013:06:25 12:60:00", "2013:06:25 12:00:60",
            "2013:06:25", "2013-06-25T12:00:00")) {
            assertEquals(40.0, scorer.score(file, 3, exif(file, date)).value, date)
        }
        for (status in ExifStatus.entries.filter { it != ExifStatus.EXTRACTED }) {
            assertEquals(40.0, scorer.score(file, 3, exif(file, status = status)).value, status.name)
        }
        val stale = exif(file).copy(input = ExifInput.from(file).copy(sizeBytes = 999))
        assertEquals(40.0, scorer.score(file, 3, stale).value)
        assertEquals(40.0, scorer.score(file, 3, null).value)
    }

    @Test
    fun partialUnknownAndInvalidDirectoriesCannotBeConfirmedByInventingACompleteDate() {
        for ((folder, expected) in listOf("2013/06" to 45.0, "2013" to 10.0, "Zoo" to 0.0, "2013-06-31" to 0.0)) {
            val file = file(folder)
            assertEquals(expected, scorer.score(file, 3, exif(file)).value, folder)
        }
        val backup = file("Google Photos/2013_06_25")
        assertEquals(65.0, scorer.score(backup, 3, exif(backup)).value)
    }

    @Test
    fun cacheEvidenceTracksRelevantExifDateRatherThanExtractionClockOrCamera() {
        val file = file("2013_06_25")
        val record = exif(file)
        val original = scorer.score(file, 3, record)
        assertTrue(scorer.hasCurrentExifEvidence(original, file, record))
        val sameDate = record.copy(extractedAt = 999, result = record.result.copy(
            metadata = record.result.metadata.copy(capturedAt = "2013:06:25 01:02:03", make = "Other")))
        assertTrue(scorer.hasCurrentExifEvidence(original, file, sameDate))
        assertFalse(scorer.hasCurrentExifEvidence(original, file, exif(file, "2013:06:30 17:43:36")))
        assertFalse(scorer.hasCurrentExifEvidence(original, file, null))
        assertFalse(scorer.hasCurrentExifEvidence(scorer.score(file, 3), file, record))
    }

    @Test
    fun customExifWeightsAreCentralizedAndIdentitySensitive() {
        val file = file("2013_06_25")
        val custom = RetentionScorer(RetentionConfig(exifConfirmedDateScore = 85.0, exifDateMismatchPenalty = -60.0))
        assertEquals(85.0, custom.score(file, 3, exif(file)).value)
        assertEquals(-20.0, custom.score(file, 3, exif(file, "2013:06:30 17:43:36")).value)
        assertFalse(custom.identity == scorer.identity)
    }

    @Test
    fun exifScoresAreIndependentOfFileOrderAndExtractionClock() {
        val files = listOf(file("2013-06-25"), file("2013_06_25"), file("2013_06_30"), file("Zoo"))
        fun scores(ordered: List<FileRecord>, extractedAt: Long) = ordered.associate { file ->
            file.path to scorer.score(file, 3, exif(file).copy(extractedAt = extractedAt))
        }
        assertEquals(scores(files, 2), scores(files.reversed(), 999))
        val leap = file("2000_02_29")
        assertEquals(90.0, scorer.score(leap, 3, exif(leap, "2000:02:29 00:00:00")).value)
    }

    @Test
    fun storedExifResolvesTheReportedGroupTieWithVisibleDateExplanations() {
        val wrongFolder = file("2013_06_25")
        val matchingFolder = file("2013_06_30")
        val group = DuplicateCandidateGroup(wrongFolder.name, wrongFolder.sizeBytes, listOf(wrongFolder, matchingFolder))
        val before = group.files.associate { it.path to scorer.score(it, 3) }
        assertTrue(presentRetentionGroup(group, before, scorer.identity, scorer.ambiguityMargin)
            .values.all { it.rank == RetentionRank.TIED })
        val after = group.files.associate { it.path to scorer.score(it, 3, exif(it, "2013:06:30 17:43:36")) }
        val presentation = presentRetentionGroup(group, after, scorer.identity, scorer.ambiguityMargin)
        assertEquals(RetentionRank.HIGHEST, presentation.getValue(matchingFolder.path).rank)
        assertEquals(RetentionRank.LOWER, presentation.getValue(wrongFolder.path).rank)
        assertTrue(presentation.values.all { "2013-06-30" in it.mainReason && "EXIF" in it.mainReason })
    }
}
