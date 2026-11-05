package com.doronyg.photoarchivededuplicator.recommendation

import com.doronyg.photoarchivededuplicator.classification.*
import com.doronyg.photoarchivededuplicator.exif.*
import com.doronyg.photoarchivededuplicator.retention.RetentionScoreService
import com.doronyg.photoarchivededuplicator.scan.DirectoryListing
import com.doronyg.photoarchivededuplicator.scan.concurrencyTest
import kotlin.test.*

class ExifFolderDateMismatchServiceTest {
    @Test
    fun regularGroupsAreRegisteredWithoutSuspectedSizeLimitAndUseStoredEvidenceOnly() = concurrencyTest { f ->
        val correct = f.root / "2018-08-26"
        val incorrect = f.root / "2018-08-09"
        val profile = OllamaSettings("http://ollama.test", "current-model").context()
        for (directory in listOf(correct, incorrect)) {
            f.repository.seedRoot(directory)
            f.repository.recordListing(directory, DirectoryListing(emptyList(), listOf(
                DirectoryListing.Entry(directory / "DSC08256.JPG", 80_000, 1))), 3)
            val file = f.repository.filesIn(directory).single()
            f.repository.saveFileExif(ExifRecord(ExifInput.from(file), ExifReadResult(ExifStatus.EXTRACTED,
                ExifMetadata(capturedAt = "2018:08:26 12:00:00")), 4))
            f.repository.saveDirectoryClassification(DirectoryClassificationRecord(directory, directory.toString(),
                DirectoryClassification.PREFERRED, profile, 5))
        }
        val retention = RetentionScoreService(f.repository, dispatcher = f.dispatcher)
        val source = f.repository.duplicateCandidates()
        assertEquals(1, source.size)
        assertTrue(f.repository.suspectedDuplicates().isEmpty())
        val service = DeletionRecommendationService(f.repository, retention, f.dispatcher, profile)
        assertEquals(0L, service.load().summary.groupCount, "Missing scores must not trigger rescoring")
        retention.assess(source)
        val scores = retention.stored(source)
        val exif = f.repository.fileExif(source.flatMap { it.files }.map { it.path })
        val statements = f.driver.statements.size
        f.fs.onBlockingCall = { error("Date mismatch category must not read archive files") }
        val report = service.load()
        assertEquals(listOf("same-directory-metadata", "exif-folder-date-mismatch", "regular-duplicates-full-confidence"),
            report.categories.map { it.category.id })
        assertTrue(report.categories.single { it.category.id == "regular-duplicates-full-confidence" }.groups.isEmpty())
        val category = report.categories.single { it.category.id == "exif-folder-date-mismatch" }
        assertEquals("EXIF and folder date mismatch", category.category.name)
        assertEquals(DeletionRecommendationSummary(1, FileStatistics(1, 80_000)), category.summary)
        assertEquals(category.summary, report.summary)
        val group = category.groups.single()
        assertEquals(correct / "DSC08256.JPG", group.survivors.single().advice.file.path)
        assertEquals(incorrect / "DSC08256.JPG", group.candidates.single().advice.file.path)
        assertEquals("2018-08-09", group.candidates.single().evidence.toMap().getValue("Directory date"))
        assertTrue(f.driver.statements.drop(statements).all { it.startsWith("SELECT") || it.startsWith("WITH") })
        assertEquals(scores, retention.stored(source))
        assertEquals(exif, f.repository.fileExif(source.flatMap { it.files }.map { it.path }))

        for (directory in listOf(correct, incorrect)) {
            f.repository.saveDirectoryClassification(DirectoryClassificationRecord(directory, directory.toString(),
                DirectoryClassification.PROTECTED, profile, 6))
            assertEquals(0L, service.load().summary.groupCount, "Protection on either directory excludes the entire group")
            f.repository.saveDirectoryClassification(DirectoryClassificationRecord(directory, directory.toString(),
                DirectoryClassification.PREFERRED, profile, 7))
        }
        assertEquals(1L, service.load().summary.groupCount)
        f.repository.recordListing(incorrect, DirectoryListing(emptyList(), listOf(
            DirectoryListing.Entry(incorrect / "DSC08256.JPG", 80_000, 99))), 8)
        assertEquals(0L, service.load().summary.groupCount, "Changed inventory metadata invalidates stored evidence")
    }
}
