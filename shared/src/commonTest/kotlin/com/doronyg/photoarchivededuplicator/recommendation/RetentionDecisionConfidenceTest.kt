package com.doronyg.photoarchivededuplicator.recommendation

import com.doronyg.photoarchivededuplicator.exif.ExifInput
import com.doronyg.photoarchivededuplicator.exif.ExifMetadata
import com.doronyg.photoarchivededuplicator.exif.ExifReadResult
import com.doronyg.photoarchivededuplicator.exif.ExifRecord
import com.doronyg.photoarchivededuplicator.exif.ExifStatus
import com.doronyg.photoarchivededuplicator.retention.RetentionPresentation
import com.doronyg.photoarchivededuplicator.retention.RetentionRank
import com.doronyg.photoarchivededuplicator.retention.RetentionScorer
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RetentionDecisionConfidenceTest {
    private val scorer = RetentionScorer()
    private val engine = DuplicateRecommendationEngine(scorer)

    private fun file(name: String, directory: String = "/Photos/2023-02-18"): FileRecord {
        val folder = directory.toPath()
        return FileRecord(folder / name, folder, name, "jpg", 120_000, 1, 2, 3)
    }

    private fun exif(files: List<FileRecord>) = files.associate { file ->
        file.path to ExifRecord(ExifInput.from(file), ExifReadResult(ExifStatus.EXTRACTED,
            ExifMetadata(capturedAt = "2023:02:18 12:44:25", make = "Canon", model = "Camera",
                width = 4000, height = 3000)), 4)
    }

    private fun scores(files: List<FileRecord>, records: Map<Path, ExifRecord>) =
        files.associate { it.path to RetentionPresentation(scorer.score(it, 5, records[it.path]), RetentionRank.CLOSE) }

    @Test
    fun duplicateAndDecisionConfidenceAreIndependentAndScoresAreNeverChanged() {
        val files = listOf(file("IMG.jpg"), file("IMG-001.jpg"))
        val records = exif(files)
        val scores = scores(files, records)
        val before = scores.toMap()
        val result = engine.recommend(files, records, scores)
        assertEquals(100, result.duplicateConfidence.score)
        assertEquals(files.first(), result.duplicateConfidence.referenceFile)
        assertEquals(RecommendationAction.KEEP_ONE, result.action)
        assertEquals(files.first(), result.preferredFile)
        assertDecisions(result, 90, ConfidenceReason.CLOSE_RETENTION_SCORES, ConfidenceReason.SAME_DIRECTORY)
        assertEquals(before, scores)
    }

    @Test
    fun tiedSameDirectoryVariantsChooseOneDeterministicallyWithoutInventingAnOriginal() {
        val files = listOf(file("IMG-002.jpg", "/Photos/Zoo"), file("IMG-001.jpg", "/Photos/Zoo"))
        val records = exif(files)
        val scores = scores(files, records)
        val result = engine.recommend(files, records, scores)
        assertEquals(RecommendationAction.KEEP_ONE, result.action)
        assertEquals(files.last(), result.preferredFile)
        assertEquals(listOf(files.first()), result.redundantCandidates)
        assertDecisions(result, 90, ConfidenceReason.EQUAL_RETENTION_SCORES)
        assertTrue(result.explanation.contains("representative"))
        assertFalse(result.explanation.contains("unsuffixed original"))
        assertEquals(result, engine.recommend(files.reversed(), records, scores))
    }

    @Test
    fun dateEvidenceWinsOverAnArtificiallyHigherIndividualScore() {
        val files = listOf(file("IMG.jpg"), file("IMG.jpg", "/Photos/2023_02_19"))
        val records = exif(files)
        val scores = scores(files, records).toMutableMap()
        scores[files.last().path] = scores.getValue(files.last().path).let {
            it.copy(score = it.score!!.copy(value = 900.0))
        }
        val result = engine.recommend(files, records, scores)
        assertEquals(files.last(), result.duplicateConfidence.referenceFile)
        assertEquals(files.first(), result.preferredFile)
        assertEquals(RecommendationAction.KEEP_ONE, result.action)
        assertDecisions(result, 95, ConfidenceReason.CAPTURE_DATE_MATCHES_DIRECTORY)
    }

    @Test
    fun ambiguousDateFormatsConfidentlyPreserveEachLocationWithoutClaimingAPreference() {
        val files = listOf(file("IMG.jpg"), file("IMG.jpg", "/Photos/20230218"))
        val records = exif(files)
        val result = engine.recommend(files, records, scores(files, records))
        assertEquals(100, result.duplicateConfidence.score)
        assertEquals(RecommendationAction.KEEP_MULTIPLE, result.action)
        assertTrue(result.redundantCandidates.isEmpty())
        assertDecisions(result, 90, ConfidenceReason.NO_RELIABLE_LOCATION_PREFERENCE,
            ConfidenceReason.MULTIPLE_RETAINED_LOCATIONS)
    }

    @Test
    fun distinctCollectionsPreserveOneRepresentativeEachAndOnlyReduceLocalCopies() {
        val original = file("IMG.jpg", "/Photos/Family")
        val local = file("IMG-001.jpg", "/Photos/Family")
        val zoo = file("IMG.jpg", "/Photos/Zoo")
        val files = listOf(local, zoo, original)
        val records = exif(files)
        val result = engine.recommend(files, records, scores(files, records))
        assertEquals(RecommendationAction.KEEP_MULTIPLE, result.action)
        assertEquals(setOf(original, zoo), result.mustPreserve.toSet())
        assertEquals(listOf(local), result.redundantCandidates)
        assertDecisions(result, 90)
        for (retained in result.mustPreserve) {
            assertTrue(ConfidenceReason.MULTIPLE_NONSTANDARD_DIRECTORIES in
                result.files.getValue(retained.path).retentionDecisionConfidence.reasonCodes)
        }
        assertEquals(original.path, result.files.getValue(local.path).retainedReplacementPath)
    }

    @Test
    fun backupAndCloudLocationsCanBeRedundantButUnknownCollectionsRemainPreserved() {
        for (special in listOf("Backup", "Import", "Sync", "Google Photos")) {
            val files = listOf(file("IMG.jpg"), file("IMG.jpg", "/Photos/$special/2023-02-19"))
            val records = exif(files)
            val result = engine.recommend(files, records, scores(files, records))
            assertEquals(RecommendationAction.KEEP_ONE, result.action, special)
            assertEquals(listOf(files.first()), result.mustPreserve, special)
            assertEquals(listOf(files.last()), result.redundantCandidates, special)
            assertEquals(files.first().path, result.files.getValue(files.last().path).retainedReplacementPath)
        }
        val files = listOf(file("IMG.jpg"), file("IMG.jpg", "/Photos/Zoo/2023-02-19"))
        val records = exif(files)
        val result = engine.recommend(files, records, scores(files, records))
        assertEquals(RecommendationAction.KEEP_MULTIPLE, result.action)
        assertTrue(result.redundantCandidates.isEmpty())
        assertEquals(files.toSet(), result.mustPreserve.toSet())
        assertDecisions(result, 90)
    }

    @Test
    fun explicitProtectionOverridesEvenPerfectDuplicateConfidenceAndLocalSuffixRules() {
        val files = listOf(file("IMG.jpg"), file("IMG-001.jpg"))
        val records = exif(files)
        val result = engine.recommend(files, records, scores(files, records), protectedPaths = setOf(files.last().path))
        assertEquals(100, result.duplicateConfidence.score)
        assertEquals(RecommendationAction.PROTECTED, result.action)
        assertTrue(result.redundantCandidates.isEmpty())
        assertEquals(files.toSet(), result.mustPreserve.toSet())
        assertDecisions(result, 100, ConfidenceReason.EXPLICIT_PROTECTION)
    }

    @Test
    fun missingExifIsUnavailableEvidenceAndMediumDuplicateConfidenceRequiresReview() {
        val files = listOf(file("IMG.jpg"), file("IMG-001.jpg"))
        val result = engine.recommend(files, emptyMap(), scores(files, emptyMap()))
        assertEquals(55, result.duplicateConfidence.score)
        assertEquals(ConfidenceBand.MEDIUM, result.duplicateConfidence.band)
        assertEquals(RecommendationAction.REVIEW, result.action)
        assertTrue(result.redundantCandidates.isEmpty())
        assertDecisions(result, 0, ConfidenceReason.DUPLICATE_CONFIDENCE_INSUFFICIENT)
        assertTrue(result.files.values.none { it.retentionDecisionConfidence.explanation.contains("Conditional") })
    }

    @Test
    fun oneContradictoryCameraBlocksTheWholeGroupDespiteOtherPerfectMatches() {
        val files = listOf(file("IMG.jpg"), file("IMG-001.jpg"), file("IMG-002.jpg"))
        val records = exif(files).toMutableMap()
        val last = records.getValue(files.last().path)
        records[files.last().path] = last.copy(result = last.result.copy(metadata = last.result.metadata.copy(model = "Other")))
        val result = engine.recommend(files, records, scores(files, records))
        assertTrue(result.duplicateConfidence.hasContradictions)
        assertTrue(result.duplicateConfidence.score <= 24)
        assertEquals(RecommendationAction.REVIEW, result.action)
        assertTrue(result.redundantCandidates.isEmpty())
        assertDecisions(result, 0, ConfidenceReason.CONFLICTING_EXIF_METADATA)
    }

    @Test
    fun meaningfulReasonsSupportALargeGapButAnUnsupportedNumberDoesNot() {
        val files = listOf(file("IMG.jpg"), file("IMG (2020_09_13 05_22_30 UTC).jpg"))
        val records = exif(files)
        val result = engine.recommend(files, records, scores(files, records))
        assertDecisions(result, 95, ConfidenceReason.MEANINGFUL_RETENTION_ADVANTAGE)

        val tied = listOf(file("IMG.jpg"), file("IMG.JPG"))
        val tiedExif = exif(tied)
        val artificial = scores(tied, tiedExif).toMutableMap()
        artificial[tied.first().path] = artificial.getValue(tied.first().path).let {
            it.copy(score = it.score!!.copy(value = 900.0))
        }
        val unsupported = engine.recommend(tied, tiedExif, artificial)
        assertDecisions(unsupported, 90, ConfidenceReason.SCORE_GAP_WITHOUT_SUPPORT)
    }

    @Test
    fun filenameDateFallbackReusesExistingParsingWithoutRescoringFiles() {
        val files = listOf(file("20230218_124425.jpg"), file("20230218_124425.jpg", "/Photos/2023_02_19"))
        val custom = DuplicateRecommendationEngine(scorer, DuplicateConfidenceConfig(
            filenameWeight = 50, modifiedWeight = 50, captureWeight = 0, cameraMakeWeight = 0,
            cameraModelWeight = 0, dimensionsWeight = 0,
        ))
        val scores = scores(files, emptyMap())
        val result = custom.recommend(files, emptyMap(), scores)
        assertEquals(RecommendationAction.KEEP_ONE, result.action)
        assertEquals(files.first(), result.preferredFile)
        assertDecisions(result, 95, ConfidenceReason.FILENAME_DATE_MATCHES_DIRECTORY)
        assertTrue(RecommendationReason.FILENAME_CONTRADICTS_DIRECTORY in result.files.getValue(files.last().path).reasonCodes)
        assertTrue(RecommendationReason.MATCHING_SIZE_AND_CAPTURE !in result.reasonCodes)
        assertEquals(scorer.identity.algorithmVersion, "5")
    }

    private fun assertDecisions(
        result: DuplicateGroupRecommendation,
        score: Int,
        vararg reasons: ConfidenceReason,
    ) {
        assertTrue(result.files.isNotEmpty())
        val retained = result.mustPreserve.map { it.path }.toSet()
        assertTrue(retained.isNotEmpty())
        for (recommendation in result.files.values) {
            val confidence = recommendation.retentionDecisionConfidence
            assertEquals(score, confidence.score, recommendation.file.path.toString())
            for (reason in reasons) {
                assertTrue(reason in confidence.reasonCodes, "${recommendation.file.path}: ${confidence.reasonCodes}")
            }
            if (recommendation.role == FileRecommendationRole.REDUNDANT_CANDIDATE) {
                assertTrue(recommendation.retainedReplacementPath in retained)
                assertTrue(recommendation.retainedReplacementPath != recommendation.file.path)
                assertTrue(recommendation.explanation.contains(
                    result.files.getValue(recommendation.retainedReplacementPath!!).file.name))
                assertTrue(confidence.score <= result.duplicateConfidence.score)
            } else {
                assertNull(recommendation.retainedReplacementPath)
            }
        }
    }
}
