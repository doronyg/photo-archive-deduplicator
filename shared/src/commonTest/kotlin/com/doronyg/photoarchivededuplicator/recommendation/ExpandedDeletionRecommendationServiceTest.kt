package com.doronyg.photoarchivededuplicator.recommendation

import com.doronyg.photoarchivededuplicator.classification.*
import com.doronyg.photoarchivededuplicator.exif.*
import com.doronyg.photoarchivededuplicator.retention.RetentionScoreService
import com.doronyg.photoarchivededuplicator.scan.DirectoryListing
import com.doronyg.photoarchivededuplicator.scan.concurrencyTest
import kotlin.test.*

class ExpandedDeletionRecommendationServiceTest {
    @Test
    fun folderPreservingCategoryUsesCompleteStoredSuspectedGroupWithoutFilesystemReads() = concurrencyTest { f ->
        val directories = listOf(f.root / "Family", f.root / "Albums")
        for (directory in directories) {
            f.repository.seedRoot(directory)
            f.repository.recordListing(directory, DirectoryListing(emptyList(), listOf("IMG.jpg", "IMG-001.jpg").map {
                DirectoryListing.Entry(directory / it, 120_000, 1, 2)
            }), 3)
            for (file in f.repository.filesIn(directory)) {
                f.repository.saveFileExif(ExifRecord(ExifInput.from(file), ExifReadResult(ExifStatus.EXTRACTED,
                    ExifMetadata(capturedAt = "2023:02:18 12:44:25", make = "Canon", model = "Camera",
                        width = 4000, height = 3000)), 4))
            }
        }
        val retention = RetentionScoreService(f.repository, dispatcher = f.dispatcher)
        val groups = f.repository.suspectedDuplicates()
        retention.assessSuspected(groups)
        val before = retention.storedSuspected(groups)
        val service = DeletionRecommendationService(f.repository, retention, f.dispatcher)
        val statements = f.driver.statements.size
        f.fs.onBlockingCall = { error("Recommendations must use stored metadata only") }
        val report = service.load()
        val category = report.categories.single { it.category.id == "folder-preserving-duplicates" }
        val group = category.groups.single()
        assertEquals(directories.toSet(), group.survivors.map { it.advice.file.directoryPath }.toSet())
        assertEquals(2, group.candidates.size)
        assertEquals(4, group.recommendation.files.size)
        assertEquals(before, retention.storedSuspected(groups))
        assertTrue(f.driver.statements.drop(statements).all { it.startsWith("SELECT") || it.startsWith("WITH") })
        assertTrue(report.categories.any { it.category.id == "single-protected-directory" })
    }

    @Test
    fun preferredBackupCategoryUsesCurrentProfileAndWithdrawsAfterClassificationChanges() = concurrencyTest { f ->
        val profile = OllamaSettings("http://ollama.test", "model").context()
        val preferred = f.root / "Photos" / "2023-02-18"
        val backup = f.root / "Backup"
        for ((directory, type) in listOf(preferred to DirectoryClassification.PREFERRED,
            backup to DirectoryClassification.BACKUP)) {
            f.repository.seedRoot(directory)
            f.repository.recordListing(directory, DirectoryListing(emptyList(),
                listOf(DirectoryListing.Entry(directory / "IMG.jpg", 120_000, 1, 2))), 3)
            val file = f.repository.filesIn(directory).single()
            f.repository.saveFileExif(ExifRecord(ExifInput.from(file), ExifReadResult(ExifStatus.EXTRACTED,
                ExifMetadata(capturedAt = "2023:02:18 12:44:25", make = "Canon", model = "Camera",
                    width = 4000, height = 3000)), 4))
            f.repository.saveDirectoryClassification(DirectoryClassificationRecord(directory, directory.toString(),
                type, profile, 5))
        }
        val retention = RetentionScoreService(f.repository, dispatcher = f.dispatcher)
        retention.assess(f.repository.duplicateCandidates())
        retention.assessSuspected(f.repository.suspectedDuplicates())
        val service = DeletionRecommendationService(f.repository, retention, f.dispatcher, profile)
        f.fs.onBlockingCall = { error("Recommendations must use stored metadata only") }
        fun DeletionRecommendationReport.category() =
            categories.single { it.category.id == "preferred-backup-perfect-score" }
        val report = service.load()
        assertTrue(report.category().groups.isNotEmpty())
        assertTrue(report.category().groups.all { group ->
            group.survivors.single().advice.file.path == preferred / "IMG.jpg" &&
                group.survivors.single().retention?.score?.value == 100.0 &&
                group.candidates.single().advice.file.path == backup / "IMG.jpg"
        })
        assertTrue(DeletionRecommendationService(f.repository, retention, f.dispatcher, profile.copy(model = "other"))
            .load().category().groups.isEmpty())
        f.repository.saveDirectoryClassification(DirectoryClassificationRecord(backup, backup.toString(),
            DirectoryClassification.UNKNOWN, profile, 6))
        assertTrue(service.load().category().groups.isEmpty())
    }
}
