package com.doronyg.photoarchivededuplicator.recommendation

import com.doronyg.photoarchivededuplicator.classification.DirectoryClassification
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationRecord
import com.doronyg.photoarchivededuplicator.classification.OllamaSettings
import com.doronyg.photoarchivededuplicator.exif.ExifInput
import com.doronyg.photoarchivededuplicator.exif.ExifMetadata
import com.doronyg.photoarchivededuplicator.exif.ExifReadResult
import com.doronyg.photoarchivededuplicator.exif.ExifRecord
import com.doronyg.photoarchivededuplicator.exif.ExifStatus
import com.doronyg.photoarchivededuplicator.retention.RetentionPresentation
import com.doronyg.photoarchivededuplicator.retention.RetentionRank
import com.doronyg.photoarchivededuplicator.retention.RetentionScorer
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExpandedDeletionRecommendationsTest {
    private val scorer = RetentionScorer()
    private val profile = OllamaSettings("http://ollama.test", "model").context()
    private fun file(folder: String, name: String = "IMG.jpg") =
        FileRecord("$folder/$name".toPath(), folder.toPath(), name, "jpg", 120_000, 1, 2)

    private fun snapshot(
        files: List<FileRecord>,
        classifications: Map<String, DirectoryClassification> = emptyMap(),
    ): DeletionAnalysisSnapshot {
        val exif = files.associate { file ->
            file.path to ExifRecord(ExifInput.from(file), ExifReadResult(ExifStatus.EXTRACTED,
                ExifMetadata(capturedAt = "2023:02:18 12:44:25", make = "Canon", model = "Camera",
                    width = 4000, height = 3000)), 3)
        }
        val scores = files.associate { file ->
            file.path to RetentionPresentation(scorer.score(file, 4, exif[file.path]), RetentionRank.CLOSE)
        }
        val classified = classifications.map { (folder, type) ->
            folder.toPath() to DirectoryClassificationRecord(folder.toPath(), folder, type, profile, 5)
        }.toMap()
        return DeletionAnalysisSnapshot(files, DuplicateRecommendationEngine().recommend(files, exif, scores,
            directoryClassifications = classified), exif, scores, classified)
    }

    private fun folderPreserving() = snapshot(listOf(
        file("/Photos/Family"), file("/Photos/Family", "IMG-001.jpg"),
        file("/Photos/Albums"), file("/Photos/Albums", "IMG-002.jpg")))

    private fun perfect() = snapshot(listOf(
        file("/Photos/2023-02-18"), file("/Photos/Backup")), mapOf(
        "/Photos/2023-02-18" to DirectoryClassification.PREFERRED,
        "/Photos/Backup" to DirectoryClassification.BACKUP))

    @Test
    fun folderCoverageUsesExistingEnginePlanAndPreservesAllDirectories() {
        val input = folderPreserving()
        val group = assertNotNull(FolderPreservingDuplicatesProvider().recommend(input))
        assertEquals(RecommendationAction.KEEP_MULTIPLE, group.recommendation.action)
        assertEquals(input.recommendation, group.recommendation)
        assertEquals(input.files.map { it.directoryPath }.toSet(),
            group.survivors.map { it.advice.file.directoryPath }.toSet())
        assertEquals(2, group.candidates.size)
        assertTrue(group.candidates.all { candidate ->
            group.survivors.any { it.advice.file.path == candidate.advice.retainedReplacementPath }
        })
    }

    @Test
    fun folderCoverageDoesNotRewritePlansThatEmptyAnyOriginalDirectory() {
        val provider = FolderPreservingDuplicatesProvider()
        assertNull(provider.recommend(perfect()))
        assertNull(provider.recommend(snapshot(listOf(file("/Photos/Family"), file("/Photos/Albums")))))
        assertNull(provider.recommend(snapshot(listOf(file("/Photos/Family"), file("/Photos/Family", "IMG-001.jpg")))))
    }

    @Test
    fun preferredBackupUsesEngineChoicesAndRequiresARetainedCurrentPerfectScore() {
        val input = perfect()
        assertEquals(100.0, input.retention.getValue(input.files.first().path).score?.value)
        val group = assertNotNull(PreferredBackupPerfectScoreProvider().recommend(input))
        assertEquals(input.recommendation, group.recommendation)
        assertEquals(input.files.first(), group.survivors.single().advice.file)
        assertEquals(input.files.last(), group.candidates.single().advice.file)
        val lower = input.retention.mapValues { (_, value) -> value.copy(score = value.score?.copy(value = 99.0)) }
        assertNull(PreferredBackupPerfectScoreProvider().recommend(input.copy(retention = lower)))
        val candidateOnly = input.retention.mapValues { (path, value) ->
            value.copy(score = value.score?.copy(value = if (path == input.files.last().path) 100.0 else 99.0))
        }
        assertNull(PreferredBackupPerfectScoreProvider().recommend(input.copy(retention = candidateOnly)))
    }

    @Test
    fun preferredBackupPreservesMultipleEngineKeepers() {
        val directories = listOf("/Photos/Family/2023-02-18", "/Photos/Albums/2023-02-18")
        val input = snapshot(directories.flatMap { listOf(file(it), file(it, "IMG-001.jpg")) },
            directories.associateWith { DirectoryClassification.PREFERRED })
        val group = assertNotNull(PreferredBackupPerfectScoreProvider().recommend(input))
        assertEquals(RecommendationAction.KEEP_MULTIPLE, group.recommendation.action)
        assertEquals(2, group.survivors.size)
        assertEquals(input.recommendation, group.recommendation)
    }

    @Test
    fun preferredBackupRejectsMissingUnknownProtectedAndMismatchedClassificationRecords() {
        val input = perfect()
        val provider = PreferredBackupPerfectScoreProvider()
        assertNull(provider.recommend(input.copy(directoryClassifications = emptyMap())))
        val directory = input.files.last().directoryPath
        val record = input.directoryClassifications.getValue(directory)
        for (invalid in listOf(
            record.copy(classification = DirectoryClassification.UNKNOWN),
            record.copy(classification = DirectoryClassification.PROTECTED),
            record.copy(directoryPath = "/elsewhere".toPath()),
        )) {
            assertNull(provider.recommend(input.copy(directoryClassifications =
                input.directoryClassifications + (directory to invalid))))
        }
    }

    @Test
    fun bothProvidersRejectStaleEvidenceAndUnsafePlans() {
        for ((input, recommend) in listOf(
            folderPreserving() to FolderPreservingDuplicatesProvider()::recommend,
            perfect() to PreferredBackupPerfectScoreProvider()::recommend,
        )) {
            assertNotNull(recommend(input))
            val candidate = input.recommendation.files.values.first {
                it.role == FileRecommendationRole.REDUNDANT_CANDIDATE
            }
            val path = candidate.file.path
            assertNull(recommend(input.copy(exif = input.exif - path)))
            assertNull(recommend(input.copy(retention = input.retention - path)))
            val exif = input.exif.getValue(path)
            assertNull(recommend(input.copy(exif = input.exif + (path to exif.copy(readerVersion = "old")))))
            val score = input.retention.getValue(path)
            assertNull(recommend(input.copy(retention = input.retention + (path to score.copy(rank = RetentionRank.STALE)))))
            assertNull(recommend(input.copy(files = input.files + input.files.first())))
            assertNull(recommend(input.copy(files = input.files + file("/Photos/Extra", "IMG-003.jpg"))))
            for (invalid in listOf(
                candidate.copy(retainedReplacementPath = path),
                candidate.copy(retainedReplacementPath = null),
                candidate.copy(retentionDecisionConfidence = candidate.retentionDecisionConfidence.copy(score = 74)),
            )) {
                assertNull(recommend(input.copy(recommendation = input.recommendation.copy(
                    files = input.recommendation.files + (path to invalid)))))
            }
            val confidence = input.recommendation.duplicateConfidence
            assertNull(recommend(input.copy(recommendation = input.recommendation.copy(
                duplicateConfidence = confidence.copy(assessment = confidence.assessment.copy(score = 74))))))
        }
    }

    @Test
    fun bothProvidersRejectProtectedGroupsAndContradictoryEvidence() {
        for ((input, recommend) in listOf(
            folderPreserving() to FolderPreservingDuplicatesProvider()::recommend,
            perfect() to PreferredBackupPerfectScoreProvider()::recommend,
        )) {
            val directory = input.files.first().directoryPath
            val protected = DirectoryClassificationRecord(directory, directory.toString(),
                DirectoryClassification.PROTECTED, profile, 5)
            assertNull(recommend(input.copy(directoryClassifications = input.directoryClassifications + (directory to protected))))
            val changed = input.exif.mapValues { (path, record) ->
                if (path == input.files.last().path) record.copy(result = record.result.copy(
                    metadata = record.result.metadata.copy(model = "Other camera"))) else record
            }
            val scores = input.files.associate { file ->
                file.path to RetentionPresentation(scorer.score(file, 4, changed[file.path]), RetentionRank.CLOSE)
            }
            val recommendation = DuplicateRecommendationEngine().recommend(input.files, changed, scores,
                directoryClassifications = input.directoryClassifications)
            assertNull(recommend(input.copy(exif = changed, retention = scores, recommendation = recommendation)))
        }
    }

    @Test
    fun eachNewCategoryAcceptsBothSourcesWithoutDisplayingTheSameCompleteGroupTwice() = runTest {
        for ((input, provider) in listOf(
            folderPreserving() to FolderPreservingDuplicatesProvider(),
            perfect() to PreferredBackupPerfectScoreProvider(),
        )) {
            for (source in DeletionRecommendationSource.entries) {
                val single = buildDeletionRecommendationReport(listOf(input.copy(source = source)), listOf(provider))
                assertEquals(1, single.categories.single().groups.size)
            }
            val report = buildDeletionRecommendationReport(DeletionRecommendationSource.entries.map {
                input.copy(source = it)
            }, listOf(provider))
            val groups = report.categories.single().groups
            assertEquals(1, groups.size)
        }
    }
}
