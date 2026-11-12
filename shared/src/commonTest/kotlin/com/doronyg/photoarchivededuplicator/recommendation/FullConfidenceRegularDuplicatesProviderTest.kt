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

class FullConfidenceRegularDuplicatesProviderTest {
    private val scorer = RetentionScorer()
    private val provider = FullConfidenceRegularDuplicatesProvider()
    private val profile = OllamaSettings("http://ollama.test", "model").context()
    private fun file(folder: String) = FileRecord(
        "$folder/DSC08256.JPG".toPath(), folder.toPath(), "DSC08256.JPG", "jpg", 80_000, 1, 2)
    private val keeper = file("/Photos/2018-08-26")
    private val backup = file("/Backup/2018-08-26")
    private fun snapshot(files: List<FileRecord> = listOf(keeper, backup)): DeletionAnalysisSnapshot {
        val exif = files.associate { file ->
            file.path to ExifRecord(ExifInput.from(file), ExifReadResult(ExifStatus.EXTRACTED,
                ExifMetadata(capturedAt = "2018:08:26 12:00:00", make = "Canon", model = "Camera",
                    width = 4000, height = 3000)), 3)
        }
        val scores = files.associate { file ->
            file.path to RetentionPresentation(scorer.score(file, 4, exif[file.path]), RetentionRank.CLOSE)
        }
        return DeletionAnalysisSnapshot(files, DuplicateRecommendationEngine().recommend(files, exif, scores),
            exif, scores, emptyMap(), source = DeletionRecommendationSource.REGULAR)
    }
    private fun label(file: FileRecord) = DirectoryClassificationRecord(
        file.directoryPath, file.directoryPath.toString(), DirectoryClassification.PROTECTED, profile, 5)

    @Test
    fun fullConfidenceUsesEngineSurvivorCandidatesEvidenceAndExplanations() {
        val input = snapshot()
        assertEquals(100, input.recommendation.duplicateConfidence.score)
        val result = assertNotNull(provider.recommend(input))
        assertEquals(listOf(keeper), result.survivors.map { it.advice.file })
        assertEquals(listOf(backup), result.candidates.map { it.advice.file })
        assertEquals(keeper.path, result.candidates.single().advice.retainedReplacementPath)
        assertEquals(input.recommendation, result.recommendation)
        for (file in result.survivors + result.candidates) {
            val path = file.advice.file.path
            assertEquals(input.recommendation.files.getValue(path), file.advice)
            assertEquals(input.exif.getValue(path), file.exif)
            assertEquals(input.retention.getValue(path), file.retention)
        }
        assertEquals("Regular duplicates (100/100)", provider.category.name)
        assertEquals("Same filename and size; duplicate confidence is 100/100.", result.evidenceSummary)
    }

    @Test
    fun lowerDuplicateConfidenceIsExcludedEvenWithStrongRetentionConfidence() {
        val valid = snapshot()
        assertTrue(valid.recommendation.files.getValue(backup.path).retentionDecisionConfidence.score >= 75)
        for (score in listOf(0, 74, 75, 95, 99)) {
            val confidence = valid.recommendation.duplicateConfidence
            assertNull(provider.recommend(valid.copy(recommendation = valid.recommendation.copy(
                duplicateConfidence = confidence.copy(assessment = confidence.assessment.copy(score = score))))))
        }
        val changedTimestamp = snapshot(listOf(keeper, backup.copy(modifiedAt = 99)))
        assertEquals(75, changedTimestamp.recommendation.duplicateConfidence.score)
        assertNull(provider.recommend(changedTimestamp))
    }

    @Test
    fun onlyCompleteRegularGroupsWithExactlyMatchingNamesAndSizesQualify() {
        val valid = snapshot()
        assertNull(provider.recommend(valid.copy(source = DeletionRecommendationSource.SUSPECTED)))
        assertNull(provider.recommend(valid.copy(files = listOf(keeper))))
        assertNull(provider.recommend(valid.copy(files = listOf(keeper, keeper))))
        assertNull(provider.recommend(snapshot(listOf(keeper, backup.copy(name = "dsc08256.jpg")))))
        assertNull(provider.recommend(snapshot(listOf(keeper, backup.copy(sizeBytes = 80_001)))))
        assertNull(provider.recommend(valid.copy(files = valid.files + file("/Other"))))
        assertNull(provider.recommend(valid.copy(recommendation = valid.recommendation.copy(
            files = valid.recommendation.files - backup.path))))
    }

    @Test
    fun fullConfidenceAloneDoesNotMakePreservedOrReviewFilesRedundant() {
        val noCandidates = snapshot(listOf(keeper, file("/Photos/Family")))
        assertEquals(100, noCandidates.recommendation.duplicateConfidence.score)
        assertTrue(noCandidates.recommendation.redundantCandidates.isEmpty())
        assertNull(provider.recommend(noCandidates))
        val valid = snapshot()
        for (action in listOf(RecommendationAction.REVIEW, RecommendationAction.PROTECTED)) {
            assertNull(provider.recommend(valid.copy(recommendation = valid.recommendation.copy(action = action))))
        }
    }

    @Test
    fun preservesAllEngineSurvivorsWhenMultipleCopiesMustBeRetained() {
        val collection = file("/Photos/Family")
        val input = snapshot(listOf(keeper, collection, backup))
        assertEquals(RecommendationAction.KEEP_MULTIPLE, input.recommendation.action)
        val result = assertNotNull(provider.recommend(input))
        assertEquals(setOf(keeper, collection), result.survivors.map { it.advice.file }.toSet())
        assertEquals(listOf(backup), result.candidates.map { it.advice.file })
        assertEquals(input.recommendation, result.recommendation)
        val unresolved = input.recommendation.files.getValue(collection.path).copy(role = FileRecommendationRole.REVIEW)
        assertNull(provider.recommend(input.copy(recommendation = input.recommendation.copy(
            files = input.recommendation.files + (collection.path to unresolved)))))
    }

    @Test
    fun missingFailedOrStaleEvidenceWithdrawsPreviouslyEligibleGroups() {
        val valid = snapshot()
        for (file in valid.files) {
            assertNull(provider.recommend(valid.copy(exif = valid.exif - file.path)))
            assertNull(provider.recommend(valid.copy(retention = valid.retention - file.path)))
            val record = valid.exif.getValue(file.path)
            for (invalid in listOf(
                record.copy(readerVersion = "old"),
                record.copy(input = record.input.copy(modifiedAt = 99)),
                record.copy(result = ExifReadResult(ExifStatus.ERROR, error = "Unreadable EXIF")),
            )) assertNull(provider.recommend(valid.copy(exif = valid.exif + (file.path to invalid))))
            val stored = valid.retention.getValue(file.path)
            assertNull(provider.recommend(valid.copy(retention = valid.retention +
                (file.path to stored.copy(rank = RetentionRank.STALE)))))
            val score = assertNotNull(stored.score)
            assertNull(provider.recommend(valid.copy(retention = valid.retention +
                (file.path to stored.copy(score = score.copy(identity = score.identity.copy(algorithmVersion = "old")))))))
        }
    }

    @Test
    fun invalidReplacementDependenciesLowDecisionConfidenceAndContradictionsAreExcluded() {
        val valid = snapshot()
        val candidate = valid.recommendation.files.getValue(backup.path)
        for (invalid in listOf(
            candidate.copy(retainedReplacementPath = null),
            candidate.copy(retainedReplacementPath = backup.path),
            candidate.copy(retainedReplacementPath = "/Outside/DSC08256.JPG".toPath()),
            candidate.copy(retentionDecisionConfidence = candidate.retentionDecisionConfidence.copy(score = 74)),
        )) assertNull(provider.recommend(valid.copy(recommendation = valid.recommendation.copy(
            files = valid.recommendation.files + (backup.path to invalid)))))
        val confidence = valid.recommendation.duplicateConfidence
        assertNull(provider.recommend(valid.copy(recommendation = valid.recommendation.copy(
            duplicateConfidence = confidence.copy(assessment = confidence.assessment.copy(
                reasonCodes = confidence.assessment.reasonCodes + ConfidenceReason.CONFLICTING_EXIF_METADATA))))))
    }

    @Test
    fun anyProtectedMemberExcludesEntireGroupIncludingUnshownMembers() {
        val valid = snapshot()
        for (file in valid.files) {
            assertNull(provider.recommend(valid.copy(
                directoryClassifications = mapOf(file.directoryPath to label(file)))))
        }
        val third = file("/Photos/Family")
        val complete = snapshot(valid.files + third)
        assertNotNull(provider.recommend(complete))
        assertNull(provider.recommend(complete.copy(
            directoryClassifications = mapOf(third.directoryPath to label(third)))))
        assertNull(provider.recommend(valid.copy(files = valid.files + third,
            directoryClassifications = mapOf(third.directoryPath to label(third)))))
    }

    @Test
    fun categorySelectionEmptyResultsAndOverlappingCategoriesUseExistingRegistry() = runTest {
        val matching = file("/Photos/2018-08-09")
        val input = snapshot(listOf(keeper, matching))
        val mismatch = ExifFolderDateMismatchProvider()
        val report = buildDeletionRecommendationReport(listOf(input), listOf(mismatch, provider))
        assertEquals(listOf(mismatch.category, provider.category), report.categories.map { it.category })
        assertTrue(report.categories.all { it.summary.groupCount == 1L })
        assertEquals(1L, report.summary.groupCount)
        assertEquals(FileStatistics(1, 80_000), report.summary.candidates)
        for (file in input.files) {
            val protectedReport = buildDeletionRecommendationReport(listOf(input.copy(
                directoryClassifications = mapOf(file.directoryPath to label(file)))), listOf(mismatch, provider))
            assertTrue(protectedReport.categories.all { it.groups.isEmpty() })
            assertEquals(DeletionRecommendationSummary(), protectedReport.summary)
        }
        val empty = buildDeletionRecommendationReport(
            listOf(input.copy(source = DeletionRecommendationSource.SUSPECTED)), listOf(provider))
        assertEquals(provider.category, empty.categories.single().category)
        assertTrue(empty.categories.single().groups.isEmpty())
        assertEquals(DeletionRecommendationSummary(), empty.summary)
    }
}
