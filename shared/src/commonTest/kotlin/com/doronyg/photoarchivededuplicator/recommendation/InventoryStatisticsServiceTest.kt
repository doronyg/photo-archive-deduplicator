package com.doronyg.photoarchivededuplicator.recommendation

import com.doronyg.photoarchivededuplicator.exif.ExifInput
import com.doronyg.photoarchivededuplicator.exif.ExifMetadata
import com.doronyg.photoarchivededuplicator.exif.ExifReadResult
import com.doronyg.photoarchivededuplicator.exif.ExifRecord
import com.doronyg.photoarchivededuplicator.exif.ExifStatus
import com.doronyg.photoarchivededuplicator.retention.RetentionScoreService
import com.doronyg.photoarchivededuplicator.scan.DirectoryListing
import com.doronyg.photoarchivededuplicator.scan.concurrencyTest
import com.doronyg.photoarchivededuplicator.ui.formatRecommendationStatistics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InventoryStatisticsServiceTest {
    @Test
    fun inventoryCountsRespectExclusionsAndDeduplicateCohortsWithoutRescoringOrReadingFiles() = concurrencyTest { f ->
        val a = f.root / "a"
        val b = f.root / "b"
        val c = f.root / "c"
        val excluded = f.root / "ignored_%"
        val neighbor = f.root / "ignored_%extra"
        val entries = mapOf(
            a to listOf("dup.JPG" to 120_000L, "solo.jpg" to 1L, ".JPG" to 2L,
                "report.pdf" to 10L, "clip.mp4" to 20L, "README" to 30L),
            b to listOf("dup.JPG" to 120_000L, "report.pdf" to 10L),
            c to listOf("renamed.jpg" to 120_000L),
            excluded to listOf("dup.JPG" to 120_000L, "report.pdf" to 10L),
            neighbor to listOf("neighbor.bin" to 1L),
        )
        for ((directory, files) in entries) {
            f.repository.seedRoot(directory)
            f.repository.recordListing(directory, DirectoryListing(emptyList(), files.map { (name, size) ->
                DirectoryListing.Entry(directory / name, size, 1)
            }), 3)
            f.repository.filesIn(directory).filter { it.sizeBytes == 120_000L }.forEach {
                f.repository.saveFileExif(ExifRecord(ExifInput.from(it), ExifReadResult(ExifStatus.EXTRACTED,
                    ExifMetadata(capturedAt = "2018:08:26 12:00:00")), 4))
            }
        }
        f.repository.addExclusion(excluded)
        val regular = f.repository.duplicateCandidates()
        val suspected = f.repository.suspectedDuplicates()
        assertEquals(4, regular.flatMap { it.files }.size)
        assertEquals(3, suspected.single().files.size)
        val paths = f.repository.allFiles().map { it.path }
        val originalExif = f.repository.fileExif(paths)
        val retention = RetentionScoreService(f.repository, dispatcher = f.dispatcher)
        val service = RecommendationStatisticsService(f.repository, retention, f.dispatcher)
        val statements = f.driver.statements.size
        f.fs.onBlockingCall = { error("Inventory statistics must not read archive files") }
        val report = service.load()
        assertEquals(InventoryFileCounts(10, 5), report.inventory.all)
        assertEquals(InventoryFileCounts(5, 3), report.inventory.byFileType.getValue(StatisticsFileType.IMAGES))
        assertEquals(InventoryFileCounts(2, 2), report.inventory.byFileType.getValue(StatisticsFileType.DOCUMENTS))
        assertEquals(InventoryFileCounts(1, 0), report.inventory.byFileType.getValue(StatisticsFileType.VIDEOS))
        assertEquals(InventoryFileCounts(2, 0), report.inventory.byFileType.getValue(StatisticsFileType.OTHER))
        assertEquals(0L, report.regular.byRole.getValue(FileRecommendationRole.REDUNDANT_CANDIDATE).totals.fileCount,
            "Possible duplicate membership is independent of deletion recommendations")
        val text = formatRecommendationStatistics(report)
        assertTrue("All: 10 files | 5 possible duplicates (50.00%)" in text)
        assertTrue("Images: 5 files | 3 possible duplicates (60.00%)" in text)
        assertTrue("Documents: 2 files | 2 possible duplicates (100.00%)" in text)
        assertTrue(f.driver.statements.drop(statements).all { it.startsWith("SELECT") || it.startsWith("WITH") })
        assertTrue(f.repository.fileScores(paths).isEmpty())
        assertEquals(originalExif, f.repository.fileExif(paths))

        val renamed = originalExif.single { it.input.path == c / "renamed.jpg" }
        f.repository.saveFileExif(renamed.copy(result = ExifReadResult(ExifStatus.ERROR, error = "EXIF unavailable")))
        val updated = service.load()
        assertEquals(InventoryFileCounts(10, 4), updated.inventory.all)
        assertEquals(InventoryFileCounts(5, 2), updated.inventory.byFileType.getValue(StatisticsFileType.IMAGES))
    }
}
