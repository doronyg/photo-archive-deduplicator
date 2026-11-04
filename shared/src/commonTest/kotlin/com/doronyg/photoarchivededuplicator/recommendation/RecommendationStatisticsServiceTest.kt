package com.doronyg.photoarchivededuplicator.recommendation

import com.doronyg.photoarchivededuplicator.exif.ExifInput
import com.doronyg.photoarchivededuplicator.exif.ExifMetadata
import com.doronyg.photoarchivededuplicator.exif.ExifReadResult
import com.doronyg.photoarchivededuplicator.exif.ExifRecord
import com.doronyg.photoarchivededuplicator.exif.ExifStatus
import com.doronyg.photoarchivededuplicator.retention.RetentionScoreService
import com.doronyg.photoarchivededuplicator.scan.DirectoryListing
import com.doronyg.photoarchivededuplicator.scan.concurrencyTest
import okio.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecommendationStatisticsServiceTest {
    @Test
    fun allRootStatisticsRespectExclusionsAndKeepOverlappingCohortsSeparateWithoutSourceReadsOrWrites() = concurrencyTest { f ->
        val first = f.root / "first" / "2023-02-18"
        val second = f.root / "second" / "2023-02-19"
        val excluded = f.root / "ignored" / "2023-02-18"
        suspend fun seed(directory: Path, names: List<String>) {
            f.repository.seedRoot(directory)
            f.repository.recordListing(directory, DirectoryListing(emptyList(), names.map {
                DirectoryListing.Entry(directory / it, 120_000, 1, 2)
            }), 3)
            f.repository.filesIn(directory).forEach {
                f.repository.saveFileExif(ExifRecord(ExifInput.from(it), ExifReadResult(ExifStatus.EXTRACTED,
                    ExifMetadata(capturedAt = "2023:02:18 12:44:25")), 4))
            }
        }
        seed(first, listOf("photo.jpg", "photo-001.jpg", "photo (2020_09_13 05_22_30 UTC).jpg"))
        seed(second, listOf("photo.jpg"))
        seed(excluded, listOf("photo.jpg", "photo-001.jpg"))
        f.repository.addExclusion(excluded)
        val regular = f.repository.duplicateCandidates()
        assertEquals(2, regular.single().files.size, "All roots must participate in the same regular group")
        val suspected = f.repository.suspectedDuplicates()
        val retention = RetentionScoreService(f.repository, dispatcher = f.dispatcher)
        retention.assessSuspected(suspected)
        val beforeRegular = retention.stored(regular)
        val beforeSuspected = retention.storedSuspected(suspected)
        val paths = suspected.flatMap { it.files }.map { it.path }
        val beforeExif = f.repository.fileExif(paths)
        val beforeInventory = (listOf(first, second, excluded)).flatMap { f.repository.filesIn(it) }
        f.fs.onBlockingCall = { error("Statistics must never touch source files") }

        val statistics = RecommendationStatisticsService(f.repository, retention, f.dispatcher).load()
        assertEquals(1L, statistics.regular.groupCount)
        assertEquals(FileStatistics(2, 240_000), statistics.regular.totals)
        assertEquals(FileStatistics(2, 240_000), statistics.regular.byAction.getValue(RecommendationAction.KEEP_MULTIPLE).totals)
        assertEquals(1L, statistics.suspected.groupCount)
        assertEquals(FileStatistics(4, 480_000), statistics.suspected.totals)
        assertEquals(FileStatistics(4, 480_000), statistics.suspected.byAction.getValue(RecommendationAction.KEEP_MULTIPLE).totals)
        assertEquals(FileStatistics(2, 240_000), statistics.suspected.byRole.getValue(FileRecommendationRole.REDUNDANT_CANDIDATE).totals)
        assertEquals(FileStatistics(2, 240_000), statistics.suspected.byRole.getValue(FileRecommendationRole.PRESERVE).totals)
        assertEquals(beforeRegular, retention.stored(regular))
        assertEquals(beforeSuspected, retention.storedSuspected(suspected))
        assertEquals(beforeExif, f.repository.fileExif(paths))
        assertEquals(beforeInventory, listOf(first, second, excluded).flatMap { f.repository.filesIn(it) })
    }

    @Test
    fun missingAssessmentsCountAsReviewRatherThanStartingScoringOrExifExtraction() = concurrencyTest { f ->
        for (directory in listOf(f.root / "a", f.root / "b")) {
            f.repository.seedRoot(directory)
            f.repository.recordListing(directory, DirectoryListing(emptyList(),
                listOf(DirectoryListing.Entry(directory / "movie.mp4", 5_000_000, null))), 1)
        }
        val retention = RetentionScoreService(f.repository, dispatcher = f.dispatcher)
        val groups = f.repository.duplicateCandidates()
        f.fs.onBlockingCall = { error("No source reads") }
        val result = RecommendationStatisticsService(f.repository, retention, f.dispatcher).load()
        assertEquals(FileStatistics(2, 10_000_000), result.regular.byAction.getValue(RecommendationAction.REVIEW).totals)
        assertEquals(FileStatistics(2, 10_000_000), result.regular.byFileType.getValue(StatisticsFileType.VIDEOS))
        assertEquals(FileStatistics(), result.suspected.totals)
        assertTrue(retention.stored(groups).files.values.all { it.score == null })
        assertTrue(f.repository.fileExif(groups.flatMap { it.files }.map { it.path }).isEmpty())
    }

    @Test
    fun emptyInventoryHasExplicitZeroTotals() = concurrencyTest { f ->
        val result = RecommendationStatisticsService(f.repository,
            RetentionScoreService(f.repository, dispatcher = f.dispatcher), f.dispatcher).load()
        assertEquals(FileStatistics(), result.regular.totals)
        assertEquals(FileStatistics(), result.suspected.totals)
        assertEquals(0L, result.regular.groupCount)
        assertEquals(0L, result.suspected.groupCount)
    }
}
