package com.doronyg.photoarchivededuplicator.recommendation

import com.doronyg.photoarchivededuplicator.classification.*
import com.doronyg.photoarchivededuplicator.exif.*
import com.doronyg.photoarchivededuplicator.retention.*
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlin.test.*

class SingleProtectedDirectoryProviderTest {
    private val scorer = RetentionScorer()
    private val provider = SingleProtectedDirectoryProvider()
    private val context = OllamaSettings("http://ollama.test", "model").context()

    private fun snapshot(folders: List<String> = listOf("/Photos/Family", "/Photos/Family")): DeletionAnalysisSnapshot {
        val files = folders.mapIndexed { index, folder ->
            val name = if (index == 0) "IMG.jpg" else "IMG-00$index.jpg"
            FileRecord("$folder/$name".toPath(), folder.toPath(), name, "jpg", 120_000, 1, 2)
        }
        val exif = files.associate { file -> file.path to ExifRecord(ExifInput.from(file),
            ExifReadResult(ExifStatus.EXTRACTED, ExifMetadata(capturedAt = "2023:02:18 12:44:25",
                make = "Canon", model = "Camera", width = 4000, height = 3000)), 3) }
        val scores = files.associate { file -> file.path to
            RetentionPresentation(scorer.score(file, 4, exif[file.path]), RetentionRank.CLOSE) }
        val classifications = files.map { it.directoryPath }.distinct().associateWith {
            DirectoryClassificationRecord(it, it.toString(), DirectoryClassification.PROTECTED, context, 5)
        }
        return DeletionAnalysisSnapshot(files, DuplicateRecommendationEngine().recommend(files, exif, scores,
            directoryClassifications = classifications), exif, scores, classifications)
    }

    @Test
    fun derivesOnlyDecisionWithoutErasingProtectionAndKeepsOneRepresentative() {
        val input = snapshot(listOf("/Photos/Family", "/Photos/Family", "/Photos/Family"))
        val group = assertNotNull(provider.recommend(input))
        assertEquals(DeletionRecommendationPolicy.SINGLE_PROTECTED_DIRECTORY, group.policy)
        assertEquals(RecommendationAction.KEEP_ONE, group.recommendation.action)
        assertEquals(1, group.survivors.size)
        assertEquals(2, group.candidates.size)
        assertTrue((group.survivors + group.candidates).all {
            it.directoryClassification?.classification == DirectoryClassification.PROTECTED
        })
        assertTrue(group.candidates.all { it.advice.retainedReplacementPath == group.survivors.single().advice.file.path })
        assertEquals(DuplicateRecommendationEngine().recommend(input.files, input.exif, input.retention),
            group.recommendation)
    }

    @Test
    fun rejectsMissingChangedAndMultipleDirectoryProtectionAndExplicitProtection() {
        val input = snapshot()
        assertNull(provider.recommend(snapshot(listOf("/Photos/Family", "/Photos/Album"))))
        assertNull(provider.recommend(input.copy(directoryClassifications = emptyMap())))
        for (type in listOf(DirectoryClassification.UNKNOWN, DirectoryClassification.PREFERRED, DirectoryClassification.BACKUP)) {
            assertNull(provider.recommend(input.copy(directoryClassifications =
                input.directoryClassifications.mapValues { (_, record) -> record.copy(classification = type) })))
        }
        assertNull(provider.recommend(input.copy(directoryClassifications =
            input.directoryClassifications.mapValues { (_, record) -> record.copy(directoryPath = "/Other".toPath()) })))
        assertNull(provider.recommend(input.copy(recommendation = DuplicateRecommendationEngine().recommend(
            input.files, input.exif, input.retention, protectedPaths = setOf(input.files.first().path),
            directoryClassifications = input.directoryClassifications))))
        assertNull(provider.recommend(input.copy(files = input.files + input.files.first())))
        assertNull(provider.recommend(input.copy(files = input.files.drop(1))))
    }

    @Test
    fun rejectsLowConfidenceContradictionsAndStaleOrMissingExifAndScores() {
        val input = snapshot()
        val path = input.files.last().path
        val confidence = input.recommendation.duplicateConfidence
        assertNull(provider.recommend(input.copy(recommendation = input.recommendation.copy(
            duplicateConfidence = confidence.copy(assessment = confidence.assessment.copy(score = 74))))))
        assertNull(provider.recommend(input.copy(exif = input.exif - path)))
        assertNull(provider.recommend(input.copy(retention = input.retention - path)))
        assertNull(provider.recommend(input.copy(exif = input.exif + (path to input.exif.getValue(path).copy(readerVersion = "old")))))
        val score = input.retention.getValue(path)
        assertNull(provider.recommend(input.copy(retention = input.retention + (path to score.copy(rank = RetentionRank.STALE)))))
        assertNull(provider.recommend(input.copy(retention = input.retention + (path to score.copy(
            score = score.score!!.copy(identity = scorer.identity.copy(configurationId = "old")))))))
        val changed = input.exif + (path to input.exif.getValue(path).let {
            it.copy(result = it.result.copy(metadata = it.result.metadata.copy(model = "Other camera")))
        })
        val scores = input.files.associate { file -> file.path to
            RetentionPresentation(scorer.score(file, 4, changed[file.path]), RetentionRank.CLOSE) }
        assertNull(provider.recommend(input.copy(exif = changed, retention = scores,
            recommendation = DuplicateRecommendationEngine().recommend(input.files, changed, scores,
                directoryClassifications = input.directoryClassifications))))
    }

    @Test
    fun bothSourcesAreAdmittedOnlyByTheOptedInProviderAndDeduplicated() = runTest {
        val input = snapshot()
        for (source in DeletionRecommendationSource.entries) {
            assertEquals(1, buildDeletionRecommendationReport(listOf(input.copy(source = source)),
                listOf(provider)).categories.single().groups.size)
        }
        val report = buildDeletionRecommendationReport(DeletionRecommendationSource.entries.map { input.copy(source = it) },
            listOf(provider, SameDirectoryMetadataProvider(), FolderPreservingDuplicatesProvider(), PreferredBackupPerfectScoreProvider()))
        assertEquals(1, report.categories.first().groups.size)
        assertTrue(report.categories.drop(1).all { it.groups.isEmpty() })
    }
}
