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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DuplicateRecommendationEngineTest {
    private val scorer = RetentionScorer()
    private val engine = DuplicateRecommendationEngine(scorer)
    private val capture = "2023:02:18 12:44:25"

    private fun file(name: String, directory: String = "/Pictures/2023-02-18", size: Long = 120_000): FileRecord {
        val folder = directory.toPath()
        return FileRecord(folder / name, folder, name, "jpg", size, 1, 2, 3)
    }

    private fun exif(file: FileRecord, date: String = capture, offset: String? = null): ExifRecord =
        ExifRecord(ExifInput.from(file), ExifReadResult(ExifStatus.EXTRACTED,
            ExifMetadata(capturedAt = date, offsetOriginal = offset)), 4)

    private fun records(files: List<FileRecord>): Map<Path, ExifRecord> = files.associate { it.path to exif(it) }

    private fun scores(files: List<FileRecord>, exif: Map<Path, ExifRecord>): Map<Path, RetentionPresentation> =
        files.associate { it.path to RetentionPresentation(scorer.score(it, 5, exif[it.path]), RetentionRank.CLOSE) }

    private fun recommend(files: List<FileRecord>): DuplicateGroupRecommendation {
        val exif = records(files)
        return engine.recommend(files, exif, scores(files, exif)).also(::assertCoherentPlan)
    }

    @Test
    fun numberedSameDirectoryCopyPrefersOriginalDespiteCloseRetentionScores() {
        val original = file("20230218_124425.jpg")
        val copy = file("20230218_124425-001.jpg")
        val result = recommend(listOf(copy, original))
        assertEquals(RecommendationAction.KEEP_ONE, result.action)
        assertEquals(original, result.preferredFile)
        assertEquals(listOf(copy), result.redundantCandidates)
        assertEquals(listOf(original), result.mustPreserve)
        assertEquals(EvidenceStrength.STRONG, result.evidenceStrength)
        assertTrue(RecommendationReason.SAME_DIRECTORY_COPY_VARIANTS in result.reasonCodes)
        assertTrue(RecommendationReason.NUMBERED_COPY_SUFFIX in result.files.getValue(copy.path).reasonCodes)
        assertTrue(result.files.getValue(copy.path).explanation.contains(original.name))
        assertEquals(90, result.files.getValue(original.path).retentionDecisionConfidence.score)
        assertEquals(85, result.files.getValue(copy.path).retentionDecisionConfidence.score)
        assertEquals(original.path, result.files.getValue(copy.path).retainedReplacementPath)
        assertEquals(
            "Retain the highest-scoring same-directory representative; equal scores use a stable path tie-break, not proof of a unique original.",
            result.explanation,
        )
        val decisionExplanation = result.files.getValue(copy.path).retentionDecisionConfidence.explanation
        assertTrue(decisionExplanation.contains("Candidate only while ${original.path} remains preserved."))
        assertTrue(decisionExplanation.contains("direct replacement comparison (85/100) and group evidence (85/100)"))
        assertTrue(decisionExplanation.contains("NORMALIZED_FILENAME_MATCH: Normalized filename agrees (20230218_124425.jpg): +30."))
        assertFalse(decisionExplanation.contains("permission to delete"))
    }

    @Test
    fun backupCopiesInUnknownCollectionPreferTheLocalOriginalWithoutDiscardingTheCollection() {
        val original = file("IMG_1234.jpg", "/Pictures/Zoo")
        val copies = listOf(
            file("IMG_1234 (2020_09_13 05_22_30 UTC).jpg", "/Pictures/Zoo"),
            file("IMG_1234 (2021_12_30 01_53_40 UTC).JPG", "/Pictures/Zoo"),
        )
        val result = recommend(copies + original)
        assertEquals(RecommendationAction.KEEP_ONE, result.action)
        assertEquals(original, result.preferredFile)
        assertEquals(copies.toSet(), result.redundantCandidates.toSet())
        assertEquals(listOf(original), result.mustPreserve)
        copies.forEach {
            assertEquals("IMG_1234.jpg", result.files.getValue(it.path).normalizedFilename)
            assertTrue(RecommendationReason.BACKUP_TIMESTAMP_SUFFIX in result.files.getValue(it.path).reasonCodes)
            assertEquals(85, result.files.getValue(it.path).retentionDecisionConfidence.score)
            assertEquals(original.path, result.files.getValue(it.path).retainedReplacementPath)
        }
    }

    @Test
    fun captureDateSelectsChronologicalLocationNotTheLargestNumericalScore() {
        val correct = file("IMG_1234.jpg")
        val wrong = file("IMG_1234.jpg", "/Pictures/2023-02-19")
        val files = listOf(wrong, correct)
        val exif = records(files)
        val scores = scores(files, exif).toMutableMap()
        scores[wrong.path] = scores.getValue(wrong.path).let { it.copy(score = it.score!!.copy(value = 900.0)) }
        val result = engine.recommend(files, exif, scores)
        assertEquals(RecommendationAction.KEEP_ONE, result.action)
        assertEquals(correct, result.preferredFile)
        assertEquals(listOf(wrong), result.redundantCandidates)
        assertTrue(RecommendationReason.EXIF_MATCHES_DIRECTORY in result.files.getValue(correct.path).reasonCodes)
        assertTrue(RecommendationReason.EXIF_CONTRADICTS_DIRECTORY in result.files.getValue(wrong.path).reasonCodes)
        assertTrue(result.explanation.contains("capture date"))
        assertEquals(95, result.files.getValue(correct.path).retentionDecisionConfidence.score)
        assertEquals(85, result.files.getValue(wrong.path).retentionDecisionConfidence.score)
        assertCoherentPlan(result)
    }

    @Test
    fun identicalNamesAndDatesInUnknownDirectoriesDoNotJustifyDiscardingALocation() {
        val files = listOf(file("IMG_1234.jpg", "/Pictures/Family"), file("IMG_1234.jpg", "/Pictures/Trips"))
        val exif = records(files)
        val scores = scores(files, exif).mapValues { (path, presentation) ->
            presentation.copy(score = presentation.score!!.copy(value = if (path == files.first().path) 1000.0 else 0.0))
        }
        val result = engine.recommend(files, exif, scores)
        assertMultipleLocations(result, RecommendationReason.UNKNOWN_OR_SPECIAL_LOCATION, files)
        assertTrue(result.files.values.all { it.retentionDecisionConfidence.score == 90 })
        assertTrue(result.files.values.all { it.retentionDecisionConfidence.explanation.contains("collection") })
    }

    @Test
    fun zooAndSpecialLocationsAreNotInferredExpendableEvenWithHigherScores() {
        for (special in listOf("/Pictures/Zoo", "/Pictures/2023-02-18/Zoo",
            "/Pictures/2023-02-18 Zoo", "/Pictures/Google Photos/2023-02-18")) {
            val files = listOf(file("IMG_1234.jpg"), file("IMG_1234.jpg", special))
            val exif = records(files)
            val scores = scores(files, exif).mapValues { (path, presentation) ->
                presentation.copy(score = presentation.score!!.copy(value = if (path == files.last().path) 1000.0 else 0.0))
            }
            val result = engine.recommend(files, exif, scores)
            assertMultipleLocations(result, RecommendationReason.UNKNOWN_OR_SPECIAL_LOCATION, files)
            assertFalse(result.action == RecommendationAction.PROTECTED, "Protection is reserved, not inferred")
        }
    }

    @Test
    fun conflictingCaptureDatesOrDifferentTimesOnSameDayPreventRecommendations() {
        val files = listOf(file("IMG_1234.jpg"), file("IMG_1234-001.jpg"))
        for (different in listOf("2023:02:19 12:44:25", "2023:02:18 12:44:26")) {
            val exif = records(files).toMutableMap()
            exif[files.last().path] = exif(files.last(), different)
            assertReview(engine.recommend(files, exif, scores(files, exif)),
                RecommendationReason.CONFLICTING_CAPTURE_TIMESTAMPS, files)
        }
    }

    @Test
    fun timestampOffsetsRemainExplicitAndNoTimezoneIsInferred() {
        val files = listOf(file("IMG_1234.jpg"), file("IMG_1234-001.jpg"))
        for (offsets in listOf(listOf(null, "+00:00"), listOf("+01:00", "+02:00"), listOf("+99:99", "+99:99"))) {
            val exif = files.mapIndexed { index, file -> file.path to exif(file, offset = offsets[index]) }.toMap()
            assertEquals(RecommendationAction.REVIEW, engine.recommend(files, exif, scores(files, exif)).action)
        }
        val exif = files.associate { it.path to exif(it, offset = "+02:00") }
        assertEquals(RecommendationAction.KEEP_ONE, engine.recommend(files, exif, scores(files, exif)).action)
    }

    @Test
    fun knownSubsecondConflictsBlockRecommendationsButEquivalentFractionsDoNot() {
        val files = listOf(file("IMG_1234.jpg"), file("IMG_1234-001.jpg"))
        for ((fractions, expected) in listOf(
            listOf("1", "2") to RecommendationAction.REVIEW,
            listOf("10", "1") to RecommendationAction.KEEP_ONE,
            listOf("000", "0") to RecommendationAction.KEEP_ONE,
        )) {
            val exif = files.mapIndexed { index, file ->
                val record = exif(file)
                file.path to record.copy(result = record.result.copy(
                    metadata = record.result.metadata.copy(subsecondOriginal = fractions[index])))
            }.toMap()
            val result = engine.recommend(files, exif, scores(files, exif))
            assertEquals(expected, result.action)
            if (expected == RecommendationAction.REVIEW) {
                assertTrue(RecommendationReason.CONFLICTING_CAPTURE_SUBSECONDS in result.reasonCodes)
            }
        }
    }

    @Test
    fun missingFailedStaleOrOldReaderExifIsInsufficientEvenWithCopySuffixes() {
        val files = listOf(file("IMG_1234.jpg"), file("IMG_1234-001.jpg"))
        val original = records(files)
        val last = files.last().path
        val changes = listOf(
            original - last,
            original + (last to original.getValue(last).copy(result = ExifReadResult(ExifStatus.ERROR))),
            original + (last to original.getValue(last).copy(readerVersion = "old")),
            original + (last to original.getValue(last).copy(input = original.getValue(last).input.copy(sizeBytes = 1))),
            original + (last to exif(files.last(), "not a timestamp")),
        )
        changes.forEach { changed ->
            val result = engine.recommend(files, changed, scores(files, original))
            assertEquals(RecommendationAction.REVIEW, result.action)
            assertTrue(result.redundantCandidates.isEmpty())
            assertEquals(files.toSet(), result.mustPreserve.toSet())
            assertTrue(result.files.values.all { it.retentionDecisionConfidence.score == 0 })
            assertCoherentPlan(result)
        }
    }

    @Test
    fun missingOrStaleRetentionAndInProgressEvidenceRemainReviewOnly() {
        val files = listOf(file("IMG_1234.jpg"), file("IMG_1234-001.jpg"))
        val exif = records(files)
        val scores = scores(files, exif)
        assertReview(engine.recommend(files, exif, scores - files.last().path),
            RecommendationReason.MISSING_RETENTION_ASSESSMENT, files)
        assertTrue(engine.recommend(files, exif, scores - files.last().path).files.values.all {
            ConfidenceReason.MISSING_RETENTION_ASSESSMENT in it.retentionDecisionConfidence.reasonCodes
        })
        val oldScores = scores.mapValues { (_, presentation) ->
            presentation.copy(score = presentation.score!!.copy(identity = scorer.identity.copy(algorithmVersion = "old")))
        }
        assertReview(engine.recommend(files, exif, oldScores), RecommendationReason.STALE_RETENTION_ASSESSMENT, files)
        assertTrue(engine.recommend(files, exif, oldScores).files.values.all {
            ConfidenceReason.STALE_RETENTION_ASSESSMENT in it.retentionDecisionConfidence.reasonCodes
        })
        assertReview(engine.recommend(files, exif, scores, evidenceReady = false), RecommendationReason.EVIDENCE_UPDATING, files)
    }

    @Test
    fun suffixesNeedSameSizeAndSufficientDuplicateEvidenceButNotAUniqueOriginal() {
        val mismatched = listOf(file("IMG_1234.jpg"), file("IMG_1234-001.jpg", size = 120_001))
        assertReview(recommend(mismatched), RecommendationReason.SIZE_MISMATCH, mismatched)
        for (files in listOf(
            listOf(file("IMG_1234.jpg"), file("IMG_5678-001.jpg")),
            listOf(file("IMG_1234.jpg"), file("IMG_1234 (2023_02_29 12_00_00 UTC).jpg")),
        )) {
            assertEquals(RecommendationAction.REVIEW, recommend(files).action)
            assertTrue(recommend(files).redundantCandidates.isEmpty())
        }
        for (files in listOf(
            listOf(file("IMG_1234-001.jpg"), file("IMG_1234-002.jpg")),
            listOf(file("IMG_1234.jpg"), file("IMG_1234.JPG")),
        )) {
            assertEquals(RecommendationAction.KEEP_ONE, recommend(files).action)
            assertEquals(1, recommend(files).redundantCandidates.size)
        }
    }

    @Test
    fun localCopyRecommendationsNeverDiscardTheOnlyRepresentativeInAnotherCollection() {
        val original = file("IMG_1234.jpg", "/Pictures/Family")
        val copy = file("IMG_1234 (2020_09_13 05_22_30 UTC).jpg", "/Pictures/Family")
        val zoo = file("IMG_1234.jpg", "/Pictures/Zoo")
        val result = recommend(listOf(copy, zoo, original))
        assertEquals(RecommendationAction.KEEP_MULTIPLE, result.action)
        assertNull(result.preferredFile)
        assertEquals(setOf(original, zoo), result.mustPreserve.toSet())
        assertEquals(listOf(copy), result.redundantCandidates)
        assertTrue(RecommendationReason.MULTIPLE_RETAINED_LOCATIONS in result.reasonCodes)
        assertTrue(result.files.getValue(zoo.path).explanation.contains("location"))
    }

    @Test
    fun dateOnlyFoldersUnderDifferentCollectionRootsAreNeverTreatedAsInterchangeable() {
        val files = listOf(file("IMG_1234.jpg", "/Pictures/Family/2023-02-19"),
            file("IMG_1234.jpg", "/Pictures/Zoo/2023-02-18"))
        assertMultipleLocations(recommend(files), RecommendationReason.UNKNOWN_OR_SPECIAL_LOCATION, files)
    }

    @Test
    fun splitDateFoldersShareTheSameOrganizationAndKnownCopiesReferenceTheFinalKeeper() {
        val original = file("IMG_1234.jpg", "/Pictures/2023/02/18")
        val misplaced = file("IMG_1234.jpg", "/Pictures/2023/03/19")
        val copy = file("IMG_1234-001 (2020_09_13 05_22_30 UTC).jpg", "/Pictures/2023/03/19")
        val result = recommend(listOf(copy, misplaced, original))
        assertEquals(RecommendationAction.KEEP_ONE, result.action)
        assertEquals(original, result.preferredFile)
        assertEquals(setOf(copy, misplaced), result.redundantCandidates.toSet())
        assertTrue(result.files.getValue(copy.path).explanation.contains(original.path.toString()))
        assertTrue(RecommendationReason.SAME_DIRECTORY_COPY_VARIANTS in result.reasonCodes)
    }

    @Test
    fun equalCaptureDateInTwoDateFormatsIsNotDecidedByFormatBonus() {
        val files = listOf(file("IMG_1234.jpg"), file("IMG_1234.jpg", "/Pictures/2023_02_18"))
        assertEquals(RecommendationAction.KEEP_MULTIPLE, recommend(files).action)
        assertNull(recommend(files).preferredFile)
    }

    @Test
    fun matchingDateDoesNotOverrideExistingFilenameDateProtection() {
        val files = listOf(file("20230219_124425.jpg"), file("20230219_124425.jpg", "/Pictures/2023-02-19"))
        val result = recommend(files)
        assertMultipleLocations(result, RecommendationReason.DIRECTORY_EVIDENCE_CONFLICT, files)
    }

    @Test
    fun recommendationsAreInvariantUnderGroupOrderAndHaveStablePerFileReasons() {
        val files = listOf(file("IMG_1234.jpg"), file("IMG_1234-001.jpg"),
            file("IMG_1234 (2020_09_13 05_22_30 UTC)-002.jpg"))
        val expected = recommend(files)
        assertNotNull(expected.preferredFile)
        assertEquals(expected, recommend(files.reversed()))
        assertEquals(expected, recommend(files.drop(1) + files.first()))
        assertTrue(expected.files.values.all { it.explanation.isNotBlank() && it.reasonCodes.isNotEmpty() })
    }

    @Test
    fun emptySingletonAndRepeatedPathsDoNotPretendToBeDuplicateGroups() {
        val original = file("IMG_1234.jpg")
        assertEquals(RecommendationAction.REVIEW, recommend(emptyList()).action)
        assertEquals(RecommendationAction.REVIEW, recommend(listOf(original)).action)
        assertEquals(RecommendationAction.REVIEW, recommend(listOf(original, original)).action)
    }

    private fun assertReview(
        result: DuplicateGroupRecommendation,
        reason: RecommendationReason,
        files: List<FileRecord>,
    ) {
        assertEquals(RecommendationAction.REVIEW, result.action)
        assertNull(result.preferredFile)
        assertTrue(result.redundantCandidates.isEmpty())
        assertEquals(files.toSet(), result.mustPreserve.toSet())
        assertTrue(reason in result.reasonCodes, result.reasonCodes.toString())
        assertTrue(result.explanation.isNotBlank())
        assertTrue(result.files.values.all { it.retentionDecisionConfidence.score == 0 })
        assertCoherentPlan(result)
    }

    private fun assertMultipleLocations(
        result: DuplicateGroupRecommendation,
        reason: RecommendationReason,
        files: List<FileRecord>,
    ) {
        assertEquals(RecommendationAction.KEEP_MULTIPLE, result.action)
        assertNull(result.preferredFile)
        assertTrue(result.redundantCandidates.isEmpty())
        assertEquals(files.toSet(), result.mustPreserve.toSet())
        assertTrue(reason in result.reasonCodes)
        assertTrue(result.explanation.isNotBlank())
        assertTrue(result.files.values.all { it.retentionDecisionConfidence.score == 90 })
        assertCoherentPlan(result)
    }

    private fun assertCoherentPlan(result: DuplicateGroupRecommendation) {
        val retained = result.mustPreserve.map { it.path }.toSet()
        if (result.files.isNotEmpty()) assertTrue(retained.isNotEmpty())
        for (recommendation in result.files.values) {
            if (recommendation.role == FileRecommendationRole.REDUNDANT_CANDIDATE) {
                val replacement = assertNotNull(recommendation.retainedReplacementPath)
                assertTrue(replacement in retained)
                assertTrue(replacement != recommendation.file.path)
                assertTrue(recommendation.explanation.contains(result.files.getValue(replacement).file.name))
                assertTrue(recommendation.retentionDecisionConfidence.score <= result.duplicateConfidence.score)
            } else {
                assertNull(recommendation.retainedReplacementPath)
            }
        }
    }
}
