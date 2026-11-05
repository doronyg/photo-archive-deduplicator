package com.doronyg.photoarchivededuplicator.recommendation

import com.doronyg.photoarchivededuplicator.classification.*
import com.doronyg.photoarchivededuplicator.exif.*
import com.doronyg.photoarchivededuplicator.retention.RetentionScoreService
import com.doronyg.photoarchivededuplicator.scan.DirectoryListing
import com.doronyg.photoarchivededuplicator.scan.concurrencyTest
import okio.Path
import kotlin.test.*

class DeletionRecommendationServiceTest {
    @Test
    fun crossDirectoryMembersAreNotHiddenAndStaleExifWithdrawsEarlierCandidates() = concurrencyTest { f ->
        val retention = RetentionScoreService(f.repository, dispatcher = f.dispatcher)
        val service = DeletionRecommendationService(f.repository, retention, f.dispatcher)
        val family = f.root / "Family"
        val other = f.root / "Other"
        suspend fun seed(directory: Path, names: List<String>) {
            f.repository.seedRoot(directory)
            f.repository.recordListing(directory, DirectoryListing(emptyList(),
                names.map { DirectoryListing.Entry(directory / it, 120_000, 1, 2) }), 3)
            f.repository.filesIn(directory).forEach { file ->
                f.repository.saveFileExif(ExifRecord(ExifInput.from(file), ExifReadResult(ExifStatus.EXTRACTED,
                    ExifMetadata(capturedAt = "2023:02:18 12:44:25", make = "Canon", model = "Camera",
                        width = 4000, height = 3000)), 4))
            }
        }
        seed(family, listOf("IMG.jpg", "IMG-001.jpg"))
        retention.assessSuspected(f.repository.suspectedDuplicates())
        assertEquals(1L, service.load().summary.groupCount)
        seed(other, listOf("IMG-002.jpg"))
        retention.assessSuspected(f.repository.suspectedDuplicates())
        assertEquals(0L, service.load().summary.groupCount, "Never select a same-directory subset of a larger group")
        f.repository.addExclusion(other)
        assertEquals(1L, service.load().summary.groupCount)
        val candidate = f.repository.filesIn(family).first { it.name == "IMG-001.jpg" }
        val exif = f.repository.fileExif(listOf(candidate.path)).single()
        f.repository.saveFileExif(exif.copy(readerVersion = "old-reader"))
        assertEquals(0L, service.load().summary.groupCount)
        f.repository.saveFileExif(exif.copy(result = exif.result.copy(
            metadata = exif.result.metadata.copy(capturedAt = "2023:02:19 12:44:25"))))
        assertEquals(0L, service.load().summary.groupCount, "Changed capture evidence must not reuse the old decision")
    }

    @Test
    fun readsStoredEvidenceOnlyExcludesFoldersAndUsesWholeGroupsWithoutRescoring() = concurrencyTest { f ->
        val family = f.root / "Family"
        val excluded = f.root / "Excluded"
        for (directory in listOf(family, excluded)) {
            f.repository.seedRoot(directory)
            f.repository.recordListing(directory, DirectoryListing(emptyList(),
                listOf("IMG.jpg", "IMG-001.jpg").map { DirectoryListing.Entry(directory / it, 120_000, 1, 2) }), 3)
            f.repository.filesIn(directory).forEach { file ->
                f.repository.saveFileExif(ExifRecord(ExifInput.from(file), ExifReadResult(ExifStatus.EXTRACTED,
                    ExifMetadata(capturedAt = if (directory == family) "2023:02:18 12:44:25" else "2023:02:19 12:44:25",
                        make = "Canon", model = "Camera", width = 4000, height = 3000)), 4))
            }
        }
        f.repository.addExclusion(excluded)
        val retention = RetentionScoreService(f.repository, dispatcher = f.dispatcher)
        val source = f.repository.suspectedDuplicates()
        retention.assessSuspected(source)
        val before = retention.storedSuspected(source)
        val profile = OllamaSettings("http://ollama.test", "current-model").context()
        f.repository.saveDirectoryClassification(DirectoryClassificationRecord(family, family.toString(),
            DirectoryClassification.PROTECTED, profile, 5))
        f.repository.saveDirectoryClassification(DirectoryClassificationRecord(family, family.toString(),
            DirectoryClassification.BACKUP, profile.copy(model = "other-model"), 6))
        val statements = f.driver.statements.size
        f.fs.onBlockingCall = { error("Review screen must not access files") }
        val report = DeletionRecommendationService(f.repository, retention, f.dispatcher, profile).load()
        val group = report.categories.single().groups.single()
        assertEquals(family / "IMG.jpg", group.survivors.single().advice.file.path)
        assertEquals(family / "IMG-001.jpg", group.candidates.single().advice.file.path)
        assertEquals(DirectoryClassification.PROTECTED, group.survivors.single().directoryClassification?.classification)
        assertEquals(FileStatistics(1, 120_000), report.summary.candidates)
        assertTrue(f.driver.statements.drop(statements).all { it.startsWith("SELECT") || it.startsWith("WITH") })
        assertEquals(before, retention.storedSuspected(source))
    }

    @Test
    fun missingScoresAndEmptyInventoryProduceEmptyCategories() = concurrencyTest { f ->
        val retention = RetentionScoreService(f.repository, dispatcher = f.dispatcher)
        val service = DeletionRecommendationService(f.repository, retention, f.dispatcher)
        assertEquals(0L, service.load().summary.groupCount)
        f.repository.seedRoot(f.root)
        f.repository.recordListing(f.root, DirectoryListing(emptyList(),
            listOf("IMG.jpg", "IMG-001.jpg").map { DirectoryListing.Entry(f.root / it, 120_000, 1, 2) }), 3)
        f.repository.filesIn(f.root).forEach { file ->
            f.repository.saveFileExif(ExifRecord(ExifInput.from(file), ExifReadResult(ExifStatus.EXTRACTED,
                ExifMetadata(capturedAt = "2023:02:18 12:44:25", make = "Canon", model = "Camera",
                    width = 4000, height = 3000)), 4))
        }
        assertEquals(0L, service.load().summary.groupCount)
        assertTrue(retention.storedSuspected(f.repository.suspectedDuplicates()).files.values.all { it.score == null })
    }
}
