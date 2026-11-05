package com.doronyg.photoarchivededuplicator.recommendation

import com.doronyg.photoarchivededuplicator.classification.*
import com.doronyg.photoarchivededuplicator.exif.*
import com.doronyg.photoarchivededuplicator.retention.*
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlin.test.*

class ExifFolderDateMismatchProviderTest {
    private val scorer = RetentionScorer()
    private val profile = OllamaSettings("http://ollama.test", "model").context()
    private fun file(folder: String, modifiedAt: Long? = 1) = FileRecord(
        "/Photos/$folder/DSC08256.JPG".toPath(), "/Photos/$folder".toPath(),
        "DSC08256.JPG", "jpg", 80_000, modifiedAt, 2)
    private val keeper = file("2018-08-26")
    private val candidate = file("2018-08-09")
    private fun snapshot(
        files: List<FileRecord> = listOf(keeper, candidate),
        captures: List<String?> = files.map { "2018:08:26 12:00:00" },
        classifications: Map<okio.Path, DirectoryClassificationRecord> = emptyMap(),
        cameraEvidence: Boolean = false,
    ): DeletionAnalysisSnapshot {
        val exif = files.zip(captures).associate { (file, capture) ->
            file.path to ExifRecord(ExifInput.from(file), ExifReadResult(ExifStatus.EXTRACTED,
                ExifMetadata(capturedAt = capture, make = if (cameraEvidence) "Canon" else null,
                    model = if (cameraEvidence) "Camera" else null,
                    width = if (cameraEvidence) 4000 else null, height = if (cameraEvidence) 3000 else null)), 3)
        }
        val scores = files.associate { file ->
            file.path to RetentionPresentation(scorer.score(file, 4, exif[file.path]), RetentionRank.CLOSE)
        }
        return DeletionAnalysisSnapshot(files,
            DuplicateRecommendationEngine().recommend(files, exif, scores, directoryClassifications = classifications),
            exif, scores, classifications, source = DeletionRecommendationSource.REGULAR)
    }
    private fun label(file: FileRecord, classification: DirectoryClassification) =
        DirectoryClassificationRecord(file.directoryPath, file.directoryPath.toString(), classification, profile, 5)

    @Test
    fun equalExifDatesMatchingKeeperAndMismatchingCandidateQualifyWithoutCameraOrDimensions() {
        val input = snapshot()
        val result = assertNotNull(ExifFolderDateMismatchProvider().recommend(input))
        assertEquals(keeper, result.survivors.single().advice.file)
        assertEquals(candidate, result.candidates.single().advice.file)
        assertEquals(keeper.path, result.candidates.single().advice.retainedReplacementPath)
        assertEquals(input.recommendation, result.recommendation)
        assertEquals(input.recommendation.files.getValue(candidate.path).explanation, result.candidates.single().advice.explanation)
        assertEquals(listOf("Directory date" to "2018-08-26", "EXIF capture date" to "2018-08-26",
            "Date comparison" to "Matches"), result.survivors.single().evidence)
        assertEquals(listOf("Directory date" to "2018-08-09", "EXIF capture date" to "2018-08-26",
            "Date comparison" to "Does not match"), result.candidates.single().evidence)
        assertTrue("Shared capture date: 2018-08-26" in result.evidenceSummary)
    }

    @Test
    fun bothDatesMatchingOrNeitherMatchingDoNotQualify() {
        val provider = ExifFolderDateMismatchProvider()
        assertNull(provider.recommend(snapshot(listOf(keeper, file("2018_08_26")))))
        assertNull(provider.recommend(snapshot(listOf(file("2018-08-25"), candidate))))
    }

    @Test
    fun unequalMissingFailedInvalidOrStaleExifExcludesTheGroup() {
        val provider = ExifFolderDateMismatchProvider()
        assertNull(provider.recommend(snapshot(captures = listOf("2018:08:26 12:00:00", "2018:08:09 12:00:00"))))
        val valid = snapshot()
        for (file in valid.files) {
            assertNull(provider.recommend(valid.copy(exif = valid.exif - file.path)))
            val record = valid.exif.getValue(file.path)
            for (invalid in listOf(
                record.copy(result = ExifReadResult(ExifStatus.ERROR, error = "Unreadable EXIF")),
                record.copy(result = record.result.copy(metadata = record.result.metadata.copy(capturedAt = null))),
                record.copy(result = record.result.copy(metadata = record.result.metadata.copy(capturedAt = "invalid"))),
                record.copy(readerVersion = "old"),
                record.copy(input = record.input.copy(sizeBytes = 99)),
            )) assertNull(provider.recommend(valid.copy(exif = valid.exif + (file.path to invalid))))
        }
        assertNull(provider.recommend(valid.copy(retention = emptyMap())))
        assertNull(provider.recommend(valid.copy(retention = valid.retention.mapValues { (_, value) ->
            value.copy(rank = RetentionRank.STALE)
        })))
    }

    @Test
    fun protectedClassificationExcludesEntireGroupRegardlessOfExistingStrongDecision() {
        val valid = snapshot()
        assertEquals(RecommendationAction.KEEP_ONE, valid.recommendation.action)
        for (file in valid.files) {
            val input = valid.copy(directoryClassifications = mapOf(file.directoryPath to label(file, DirectoryClassification.PROTECTED)))
            assertNull(ExifFolderDateMismatchProvider().recommend(input))
        }
        val protectedThird = file("2018-08-01")
        assertNull(ExifFolderDateMismatchProvider().recommend(valid.copy(files = valid.files + protectedThird,
            directoryClassifications = mapOf(protectedThird.directoryPath to label(protectedThird, DirectoryClassification.PROTECTED)))))
    }

    @Test
    fun bothDirectoriesPreferredStillQualify() {
        val input = snapshot(classifications = listOf(keeper, candidate).associate {
            it.directoryPath to label(it, DirectoryClassification.PREFERRED)
        })
        assertNotNull(ExifFolderDateMismatchProvider().recommend(input))
    }

    @Test
    fun existingDecisionMustKeepCorrectlyLocatedFileAndProvideDirectReplacement() {
        val valid = snapshot()
        val oldKeeper = valid.recommendation.files.getValue(keeper.path)
        val oldCandidate = valid.recommendation.files.getValue(candidate.path)
        val reversed = valid.recommendation.copy(files = mapOf(
            keeper.path to oldKeeper.copy(role = FileRecommendationRole.REDUNDANT_CANDIDATE, retainedReplacementPath = candidate.path),
            candidate.path to oldCandidate.copy(role = FileRecommendationRole.PREFERRED, retainedReplacementPath = null),
        ))
        val provider = ExifFolderDateMismatchProvider()
        assertNull(provider.recommend(valid.copy(recommendation = reversed)))
        assertNull(provider.recommend(valid.copy(recommendation = valid.recommendation.copy(action = RecommendationAction.REVIEW))))
        assertNull(provider.recommend(valid.copy(recommendation = valid.recommendation.copy(
            files = valid.recommendation.files + (candidate.path to oldCandidate.copy(retainedReplacementPath = null))))))
    }

    @Test
    fun usesExistingRecognitionRatherThanArbitraryEmbeddedOrPartialDates() {
        val provider = ExifFolderDateMismatchProvider()
        for (folder in listOf("trip on 2018-08-09", "2018-08", "2018/08", "2018-02-30")) {
            assertNull(provider.recommend(snapshot(listOf(keeper, file(folder)))))
        }
        assertNotNull(provider.recommend(snapshot(listOf(file("2018/08/26"), file("2018/08/09")))))
    }

    @Test
    fun filesystemTimestampsAreNotAdditionalEligibilityCriteria() {
        val input = snapshot(listOf(file("2018-08-26", null), file("2018-08-09", null)), cameraEvidence = true)
        assertNotNull(ExifFolderDateMismatchProvider().recommend(input))
    }

    @Test
    fun onlyRegisteredRegularGroupsReachThisProvider() = runTest {
        val provider = ExifFolderDateMismatchProvider()
        assertEquals(1L, buildDeletionRecommendationReport(listOf(snapshot()), listOf(provider)).summary.groupCount)
        assertEquals(0L, buildDeletionRecommendationReport(listOf(
            snapshot().copy(source = DeletionRecommendationSource.SUSPECTED)), listOf(provider)).summary.groupCount)
    }

    @Test
    fun onlyMismatchingDirectoryCandidatesAreDisplayedAndAllMembersStillParticipateInSafety() {
        val otherMatchingCopy = file("2018_08_26")
        val input = snapshot(listOf(keeper, candidate, otherMatchingCopy), classifications = mapOf(
            keeper.directoryPath to label(keeper, DirectoryClassification.PREFERRED),
            candidate.directoryPath to label(candidate, DirectoryClassification.BACKUP),
            otherMatchingCopy.directoryPath to label(otherMatchingCopy, DirectoryClassification.BACKUP),
        ))
        val provider = ExifFolderDateMismatchProvider()
        val result = assertNotNull(provider.recommend(input))
        assertEquals(listOf(candidate.path), result.candidates.map { it.advice.file.path })
        assertEquals(input.recommendation, result.recommendation)
        assertNull(provider.recommend(input.copy(directoryClassifications = mapOf(
            otherMatchingCopy.directoryPath to label(otherMatchingCopy, DirectoryClassification.PROTECTED)))))
        assertNull(provider.recommend(input.copy(recommendation = input.recommendation.copy(
            files = input.recommendation.files - otherMatchingCopy.path))))
    }
}
