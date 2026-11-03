package com.doronyg.photoarchivededuplicator.retention

import com.doronyg.photoarchivededuplicator.exif.ExifInput
import com.doronyg.photoarchivededuplicator.exif.ExifMetadata
import com.doronyg.photoarchivededuplicator.exif.ExifReadResult
import com.doronyg.photoarchivededuplicator.exif.ExifRecord
import com.doronyg.photoarchivededuplicator.exif.ExifStatus
import com.doronyg.photoarchivededuplicator.scan.DirectoryListing
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import com.doronyg.photoarchivededuplicator.scan.concurrencyTest
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RebalancedRetentionWeightsTest {
    private val scorer = RetentionScorer()
    private val filename = "20191210_104020-ANIMATION.gif"

    private fun file(folder: String, name: String = filename): FileRecord {
        val directory = "/Photos/$folder".toPath()
        return FileRecord(directory / name, directory, name, "gif", 25_900_000, null, 1)
    }

    @Test
    fun bothDateMismatchPenaltiesAreFortyAndNeverDoubleCounted() {
        val file = file("Pictures/2016-06-08", "IMG-20150723-WA0003.jpg")
        val exif = ExifRecord(ExifInput.from(file), ExifReadResult(ExifStatus.EXTRACTED,
            ExifMetadata(capturedAt = "2014:01:02 03:04:05")), 2)
        assertEquals(-40.0, RetentionConfig().filenameDateMismatchPenalty)
        assertEquals(-40.0, RetentionConfig().exifDateMismatchPenalty)
        assertEquals(60.0, scorer.score(file, 3).value)
        val both = scorer.score(file, 3, exif)
        assertEquals(60.0, both.value)
        assertEquals(-40.0, both.factors.filter { it.key.endsWith("date-mismatch") }.sumOf { it.contribution })
    }

    @Test
    fun reportedGifCopyGetsTwentyAndFailedExifDoesNotChangeEitherCopy() {
        val files = listOf(
            file("MobileBackup/Doron's Galaxy S20 FE 5G/DCIM/Google Photos/2019/12") to 20.0,
            file("Pictures/.Picasa3Temp_1") to -60.0,
            file("Pictures/2019-12-10") to 100.0,
        )
        for ((file, expected) in files) {
            val withoutExif = scorer.score(file, 3)
            assertEquals(expected, withoutExif.value)
            for (status in ExifStatus.entries.filter { it != ExifStatus.EXTRACTED }) {
                val failed = ExifRecord(ExifInput.from(file), ExifReadResult(status,
                    ExifMetadata(capturedAt = "2018:01:01 00:00:00"), error = "Extraction unavailable"), 2)
                assertEquals(withoutExif, scorer.score(file, 3, failed), status.name)
            }
        }
    }

    @Test
    fun recalibrationPreservesOldConfigurationAndCachesScoresAfterLimitExceeded() = concurrencyTest { fixture ->
        val backup = fixture.root / "MobileBackup/Google Photos/2019/12"
        val temporary = fixture.root / "Pictures/.Picasa3Temp_1"
        for (directory in listOf(backup, temporary)) {
            fixture.repository.seedRoot(directory)
            fixture.repository.recordListing(directory, DirectoryListing(emptyList(), listOf(
                DirectoryListing.Entry(directory / filename, 25_900_000, null),
            )), 1)
        }
        val groups = fixture.repository.duplicateCandidates(fixture.root)
        val paths = groups.single().files.map { it.path }
        val oldScorer = RetentionScorer(RetentionConfig(
            yearMonthDateBonus = 30.0, filenameDateMismatchPenalty = -80.0, exifDateMismatchPenalty = -80.0,
        ))
        var computations = 0L
        val oldService = RetentionScoreService(fixture.repository, oldScorer,
            fixture.dispatcher, clock = { ++computations })
        val old = oldService.assess(groups)
        assertEquals(5.0, old.files.getValue(backup / filename).score!!.value)
        fixture.fs.onBlockingCall = { error("Recalibration must never open source files") }
        val currentService = RetentionScoreService(fixture.repository, scorer,
            fixture.dispatcher, clock = { ++computations })
        val current = currentService.assess(groups)
        assertEquals(20.0, current.files.getValue(backup / filename).score!!.value)
        assertEquals(-60.0, current.files.getValue(temporary / filename).score!!.value)
        assertTrue(scorer.identity != oldScorer.identity)
        for (file in groups.single().files) {
            fixture.repository.saveFileExif(ExifRecord(ExifInput.from(file),
                ExifReadResult(ExifStatus.LIMIT_EXCEEDED, error = "Read limit exceeded"), 2))
        }
        assertEquals(current, currentService.assess(groups))
        assertEquals(old, oldService.assess(groups))
        assertEquals(4L, computations, "Failed EXIF must not invalidate unchanged neutral scores")
        assertEquals(4, fixture.repository.fileScores(paths).size)
    }
}
