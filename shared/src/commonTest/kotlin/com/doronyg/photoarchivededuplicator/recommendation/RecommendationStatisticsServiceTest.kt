package com.doronyg.photoarchivededuplicator.recommendation

import com.doronyg.photoarchivededuplicator.classification.*
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
    fun outdatedOrOtherModelLabelsDoNotCountAsCoverageAndMissingScoresRemainReview() = concurrencyTest { f ->
        val current = OllamaSettings("http://ollama.test", "model").context()
        val directories = listOf(f.root / "a", f.root / "b")
        for ((index, directory) in directories.withIndex()) {
            f.repository.seedRoot(directory)
            f.repository.recordListing(directory, DirectoryListing(emptyList(), listOf(
                DirectoryListing.Entry(directory / "IMG.jpg", 120_000, 1, 2))), 3)
            val old = if (index == 0) current.copy(promptVersion = "previous") else current.copy(model = "other-model")
            f.repository.saveDirectoryClassification(DirectoryClassificationRecord(directory, directory.toString(),
                DirectoryClassification.PROTECTED, old, 4))
        }
        val retention = RetentionScoreService(f.repository, dispatcher = f.dispatcher)
        val missing = RecommendationStatisticsService(f.repository, retention, f.dispatcher, current).load()
        assertEquals(2L, missing.regular.ai!!.missing.directoryCount)
        assertEquals(0L, missing.regular.ai!!.classifiedDirectories)
        val directory = directories.first()
        f.repository.saveDirectoryClassification(DirectoryClassificationRecord(directory, directory.toString(),
            DirectoryClassification.UNKNOWN, current, 5))
        val covered = RecommendationStatisticsService(f.repository, retention, f.dispatcher, current).load()
        val ai = covered.regular.ai!!
        assertEquals(1L, ai.classifiedDirectories)
        assertEquals(1L, ai.partiallyClassifiedGroups)
        assertEquals(FileStatistics(1, 120_000), ai.byClassification.getValue(DirectoryClassification.UNKNOWN).files)
        assertEquals(0L, ai.changedGroups)
        assertEquals(1L, covered.regular.groupCounts.getValue(RecommendationAction.REVIEW))
        assertTrue(retention.stored(f.repository.duplicateCandidates()).files.values.all { it.score == null })
    }

    @Test
    fun aiCoverageAndImpactUseOnlyCurrentProfileAndCompareSameStoredEvidenceWithoutWrites() = concurrencyTest { f ->
        val normal = f.root / "Family"
        val backup = f.root / "Upload"
        val excluded = f.root / "Excluded"
        val outside = f.root / "Outside"
        val profile = OllamaSettings("http://ollama.test", "current-model").context()
        for (directory in listOf(normal, backup, excluded)) {
            f.repository.seedRoot(directory)
            f.repository.recordListing(directory, DirectoryListing(emptyList(), listOf(
                DirectoryListing.Entry(directory / "IMG.jpg", 120_000, 1, 2),
                DirectoryListing.Entry(directory / "IMG-001.jpg", 120_000, 1, 2))), 3)
            for (file in f.repository.filesIn(directory)) {
                f.repository.saveFileExif(ExifRecord(ExifInput.from(file), ExifReadResult(ExifStatus.EXTRACTED,
                    ExifMetadata(capturedAt = "2023:02:18 12:44:25", make = "Canon", model = "Camera",
                        width = 4000, height = 3000)), 4))
            }
        }
        f.repository.addExclusion(excluded)
        for ((directory, label) in listOf(normal to DirectoryClassification.PREFERRED, backup to DirectoryClassification.BACKUP,
            excluded to DirectoryClassification.PROTECTED, outside to DirectoryClassification.PROTECTED)) {
            f.repository.saveDirectoryClassification(DirectoryClassificationRecord(directory, directory.toString(), label, profile, 5))
        }
        f.repository.saveDirectoryClassification(DirectoryClassificationRecord(backup, backup.toString(),
            DirectoryClassification.PROTECTED, profile.copy(model = "other-model"), 6))
        val retention = RetentionScoreService(f.repository, dispatcher = f.dispatcher)
        val suspected = f.repository.suspectedDuplicates()
        retention.assessSuspected(suspected)
        val before = retention.storedSuspected(suspected)
        val statements = f.driver.statements.size
        f.fs.onBlockingCall = { error("AI statistics must not inspect archive files") }
        val report = RecommendationStatisticsService(f.repository, retention, f.dispatcher, profile).load()
        assertEquals(profile, report.classificationContext)
        val regularAi = report.regular.ai!!
        assertEquals(2L, regularAi.directoryCount)
        assertEquals(2L, regularAi.classifiedDirectories)
        assertEquals(FileStatistics(4, 480_000), regularAi.classifiedFiles)
        assertEquals(2L, regularAi.changedGroups)
        assertEquals(2L, regularAi.changedActionGroups)
        assertEquals(FileStatistics(2, 240_000), regularAi.addedRedundantCandidates)
        assertEquals(1L, report.suspected.ai!!.changedGroups)
        assertEquals(0L, report.suspected.ai!!.byClassification.getValue(DirectoryClassification.PROTECTED).directoryCount)
        assertTrue(f.driver.statements.drop(statements).all { it.startsWith("SELECT") || it.startsWith("WITH") })
        assertEquals(before, retention.storedSuspected(suspected))
        val noAi = RecommendationStatisticsService(f.repository, retention, f.dispatcher).load()
        assertEquals(0L, noAi.regular.ai!!.classifiedDirectories)
        assertEquals(0L, noAi.regular.ai!!.changedGroups)
        assertEquals(FileStatistics(4, 480_000), noAi.regular.ai!!.missing.files)
    }

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
