package com.doronyg.photoarchivededuplicator.recommendation

import com.doronyg.photoarchivededuplicator.exif.*
import com.doronyg.photoarchivededuplicator.retention.*
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.*

class DuplicateConfidenceTest {
    private val scorer = RetentionScorer()
    private val calculator = DuplicateConfidenceCalculator(scorer)
    private val full = ExifMetadata("2023:02:18 12:44:25", "+02:00", "1", "Camera", "Model", 1, 4000, 3000)
    private fun file(folder: String, name: String = "IMG.jpg", modified: Long? = 1001) =
        FileRecord("/$folder/$name".toPath(), "/$folder".toPath(), name, "jpg", 100, modified, 1)
    private val a = file("a")
    private val b = file("b")
    private fun record(file: FileRecord, metadata: ExifMetadata = full) =
        ExifRecord(ExifInput.from(file), ExifReadResult(ExifStatus.EXTRACTED, metadata), 2)
    private fun records(files: List<FileRecord>) = files.associate { it.path to record(it) }
    private fun assess(
        files: List<FileRecord> = listOf(a, b),
        exif: Map<Path, ExifRecord> = records(files),
        retention: Map<Path, RetentionPresentation> = emptyMap(),
    ) = calculator.assess(files, exif, retention)

    @Test fun allEvidenceEarns100AndEvidenceAddsUp() {
        val result = assess()
        assertEquals(100, result.score)
        assertEquals(ConfidenceBand.VERY_HIGH, result.band)
        assertFalse(result.hasContradictions)
        val pair = result.comparisons.getValue(b.path)
        assertEquals(100, pair.evidence.sumOf { it.points })
        assertTrue(pair.evidence.all { it.explanation.isNotBlank() })
        assertTrue(ConfidenceReason.EVIDENCE_UNAVAILABLE in result.assessment.reasonCodes)
    }

    @Test fun supportedSuffixesAndOnlyExtensionCaseAreNormalized() {
        for (name in listOf("IMG-001.JPG", "IMG (2020_09_13 05_22_30 UTC).JPG",
            "IMG-001 (2020_09_13 05_22_30 UTC).JPG")) {
            assertEquals(100, assess(listOf(a, file("b", name))).score)
        }
        assertEquals(70, assess(listOf(a, file("b", "img.jpg"))).score)
        assertEquals("IMG.jpg", normalizedRecommendationFilename("IMG-001.JPG"))
    }

    @Test fun missingFieldsDoNotRedistributePoints() {
        val files = listOf(a, b)
        assertEquals(85, assess(exif = files.associate {
            it.path to record(it, ExifMetadata(capturedAt = full.capturedAt))
        }).score)
        assertEquals(55, assess(exif = emptyMap()).score)
        val missing = listOf(a.copy(modifiedAt = null), b.copy(modifiedAt = null))
        assertEquals(75, assess(missing).score)
        val mismatch = listOf(a, b.copy(modifiedAt = 1002))
        val result = assess(mismatch)
        assertEquals(75, result.score)
        assertFalse(result.hasContradictions)
    }

    @Test fun semanticExifContradictionsAlwaysCapConfidence() {
        val conflicts = listOf(
            full.copy(capturedAt = "2023:02:18 12:44:26"),
            full.copy(offsetOriginal = "+03:00"),
            full.copy(subsecondOriginal = "2"),
            full.copy(make = "camera"),
            full.copy(model = "Other"),
            full.copy(width = 4001),
            full.copy(height = 3001),
            full.copy(orientation = 2),
            full.copy(width = 4001, height = null),
        )
        for (metadata in conflicts) {
            val result = assess(exif = mapOf(a.path to record(a), b.path to record(b, metadata)))
            assertTrue(result.hasContradictions, "$metadata")
            assertEquals(24, result.score, "$metadata")
            assertTrue(ConfidenceReason.CONFLICTING_EXIF_METADATA in result.assessment.reasonCodes)
            val pair = result.comparisons.getValue(b.path)
            assertTrue(pair.evidence.any { it.points < 0 })
            assertEquals(result.score, pair.evidence.sumOf { it.points })
        }
    }

    @Test fun missingInvalidAndEquivalentCorrelatedFieldsNeverConflict() {
        for (metadata in listOf(full.copy(subsecondOriginal = "10"),
            full.copy(offsetOriginal = null, subsecondOriginal = null, orientation = null),
            full.copy(offsetOriginal = "+99:00", subsecondOriginal = "bad", orientation = 9))) {
            val result = assess(exif = mapOf(a.path to record(a), b.path to record(b, metadata)))
            assertEquals(100, result.score)
            assertFalse(result.hasContradictions)
        }
        val missing = full.copy(capturedAt = "bad", make = " ", model = null, width = 0, height = -1)
        val result = assess(exif = mapOf(a.path to record(a), b.path to record(b, missing)))
        assertEquals(55, result.score)
        assertFalse(result.hasContradictions)
    }

    @Test fun contradictionFlagSurvivesWhenRawScoreIsAlreadyBelowCap() {
        val files = listOf(a.copy(modifiedAt = null), file("b", "different.jpg", null))
        val exif = files.mapIndexed { index, file ->
            file.path to record(file, ExifMetadata(make = if (index == 0) "One" else "Two"))
        }.toMap()
        val result = assess(files, exif)
        assertEquals(0, result.score)
        assertTrue(result.hasContradictions)
        assertTrue(result.comparisons.values.single().evidence.none { it.points < 0 })
        assertTrue(ConfidenceReason.CONFLICTING_EXIF_METADATA in result.assessment.reasonCodes)
    }

    @Test fun timestampsCompareCameraLocalSecondsAndSubsecondsNumerically() {
        val equivalent = assess(exif = mapOf(a.path to record(a, full.copy(subsecondOriginal = "000")),
            b.path to record(b, full.copy(capturedAt = " 2023:02:18 12:44:25 ", subsecondOriginal = "0"))))
        assertEquals(100, equivalent.score)
        assertFalse(equivalent.hasContradictions)
        val sameInstantDifferentLocal = assess(exif = mapOf(a.path to record(a),
            b.path to record(b, full.copy(capturedAt = "2023:02:18 11:44:25", offsetOriginal = "+01:00"))))
        assertEquals(24, sameInstantDifferentLocal.score)
        assertTrue(sameInstantDifferentLocal.hasContradictions)
        assertTrue(ConfidenceReason.CAPTURE_TIMESTAMP_CONFLICT in sameInstantDifferentLocal.assessment.reasonCodes)
        assertTrue(ConfidenceReason.CAPTURE_OFFSET_CONFLICT in sameInstantDifferentLocal.assessment.reasonCodes)
    }

    @Test fun cameraComponentsAndDimensionsAreIndependent() {
        for ((metadata, score) in listOf(full.copy(make = null) to 95,
            full.copy(model = null) to 95, full.copy(width = null) to 95,
            full.copy(height = null) to 95, full.copy(make = " Camera ", model = " Model ") to 100)) {
            assertEquals(score, assess(exif = mapOf(a.path to record(a), b.path to record(b, metadata))).score)
        }
    }

    @Test fun groupUsesWeakestComparisonAndStableOrder() {
        val c = file("c", "different.jpg")
        val files = listOf(c, b, a)
        val exif = records(files) - c.path
        val result = assess(files, exif)
        assertEquals(25, result.score)
        assertEquals(listOf(b.path, c.path), result.comparisons.keys.toList())
        assertTrue(result.assessment.explanation.contains(c.path.toString()))
        assertEquals(result, assess(files.reversed(), exif))
        val conflicting = records(files) + (c.path to record(c, full.copy(make = "other")))
        assertEquals(24, assess(files, conflicting).score)
        assertTrue(assess(files, conflicting).hasContradictions)
    }

    @Test fun knownGroupConflictsCannotHideBehindAnIncompleteHighestScoringReference() {
        val c = file("c")
        val files = listOf(c, b, a)
        val metadata = listOf(
            full.copy(make = null, model = null),
            full.copy(width = null, height = null),
            full.copy(offsetOriginal = null, subsecondOriginal = null, orientation = null),
            full.copy(capturedAt = null),
        )
        val conflicts = listOf(
            full.copy(make = "Different", model = "Other model"),
            full.copy(width = 4001, height = 3001),
            full.copy(offsetOriginal = "+03:00", subsecondOriginal = "2", orientation = 2),
            full.copy(capturedAt = "2023:02:18 12:44:26"),
        )
        for ((referenceMetadata, conflictingMetadata) in metadata.zip(conflicts)) {
            val exif = mapOf(a.path to record(a, referenceMetadata), b.path to record(b),
                c.path to record(c, conflictingMetadata))
            val scores = files.associate { file ->
                file.path to RetentionPresentation(scorer.score(file, 3, exif[file.path]).copy(
                    value = if (file == a) 1000.0 else 0.0), RetentionRank.CLOSE)
            }
            val result = assess(files, exif, scores)
            assertEquals(a, result.referenceFile)
            assertTrue(result.comparisons.values.none { it.hasContradictions })
            assertTrue(result.hasContradictions)
            assertEquals(24, result.score)
            assertTrue(ConfidenceReason.CONFLICTING_EXIF_METADATA in result.assessment.reasonCodes)
            assertTrue(result.assessment.explanation.contains(b.path.toString()))
            assertTrue(result.assessment.explanation.contains(c.path.toString()))
            assertEquals(result, assess(files.reversed(), exif, scores))
        }
    }

    @Test fun currentHighestRetentionScoreSelectsReferenceAndTiesUsePath() {
        val a = file("2023-02-18/a")
        val b = file("2023-02-18/b")
        val files = listOf(b, a)
        val exif = records(files)
        val scores = files.associate { file ->
            file.path to RetentionPresentation(scorer.score(file, 3, exif[file.path]).copy(
                value = if (file == b) 200.0 else 100.0), RetentionRank.CLOSE)
        }
        assertEquals(b, assess(files, exif, scores).referenceFile)
        val tied = scores.mapValues { (_, p) -> p.copy(score = p.score!!.copy(value = 100.0)) }
        assertEquals(a, assess(files, exif, tied).referenceFile)
        for (rank in listOf(RetentionRank.MISSING, RetentionRank.STALE, RetentionRank.INCOMPLETE)) {
            assertEquals(a, assess(files, exif, scores + (b.path to scores.getValue(b.path).copy(rank = rank))).referenceFile)
        }
        val score = scores.getValue(b.path).score!!
        for (invalid in listOf(score.copy(value = Double.NaN), score.copy(value = Double.POSITIVE_INFINITY),
            score.copy(input = score.input.copy(modifiedAt = 4)),
            score.copy(identity = score.identity.copy(algorithmVersion = "obsolete")),
            score.copy(factors = emptyList()))) {
            assertEquals(a, assess(files, exif, scores + (b.path to RetentionPresentation(invalid, RetentionRank.HIGHEST))).referenceFile)
        }
    }

    @Test fun staleFailedAndForeignExifAreUnavailable() {
        val valid = record(b)
        val variants = listOf(valid.copy(input = valid.input.copy(sizeBytes = 101)),
            valid.copy(readerId = "other"), valid.copy(readerVersion = "old")) +
            ExifStatus.entries.filter { it != ExifStatus.EXTRACTED }.map {
                valid.copy(result = valid.result.copy(status = it))
            }
        for (invalid in variants) {
            val result = assess(exif = mapOf(a.path to record(a), b.path to invalid))
            assertEquals(55, result.score)
            assertFalse(result.hasContradictions)
            assertTrue(ConfidenceReason.EVIDENCE_UNAVAILABLE in result.assessment.reasonCodes)
        }
    }

    @Test fun insufficientRepeatedOrUnequalSizesNeverFakeConfidenceAndSizeEarnsNothing() {
        for ((files, reason) in listOf(emptyList<FileRecord>() to ConfidenceReason.INSUFFICIENT_FILES,
            listOf(a) to ConfidenceReason.INSUFFICIENT_FILES,
            listOf(a, a) to ConfidenceReason.REPEATED_FILE_PATH,
            listOf(a, b.copy(sizeBytes = 101)) to ConfidenceReason.SIZE_MISMATCH)) {
            val result = assess(files)
            assertEquals(0, result.score)
            assertTrue(reason in result.assessment.reasonCodes)
        }
        assertEquals(0, assess(listOf(a.copy(modifiedAt = null),
            file("b", "other.jpg", null)), emptyMap()).score)
    }

    @Test fun configurableWeightsAndCapsAreValidatedWithoutOverflow() {
        assertEquals(55, DuplicateConfidenceCalculator(scorer,
            DuplicateConfidenceConfig(filenameWeight = 55, modifiedWeight = 0)).assess(
            listOf(a, b), emptyMap(), emptyMap()).score)
        assertFailsWith<IllegalArgumentException> { DuplicateConfidenceConfig(filenameWeight = -1) }
        assertFailsWith<IllegalArgumentException> { DuplicateConfidenceConfig(captureWeight = 31) }
        assertFailsWith<IllegalArgumentException> { DuplicateConfidenceConfig(contradictionCap = 25) }
        assertFailsWith<IllegalArgumentException> { DuplicateConfidenceConfig(contradictionCap = -1) }
        assertFailsWith<IllegalArgumentException> { DuplicateConfidenceConfig(filenameWeight = Int.MAX_VALUE) }
        val result = DuplicateConfidenceCalculator(scorer, DuplicateConfidenceConfig(contradictionCap = 0))
            .assess(listOf(a, b), mapOf(a.path to record(a), b.path to record(b, full.copy(make = "Other"))), emptyMap())
        assertEquals(0, result.score)
        assertTrue(result.hasContradictions)
    }

    @Test fun confidenceBandBoundariesAndRangeAreExplicit() {
        for ((score, band) in listOf(0 to ConfidenceBand.VERY_LOW, 24 to ConfidenceBand.VERY_LOW,
            25 to ConfidenceBand.LOW, 49 to ConfidenceBand.LOW, 50 to ConfidenceBand.MEDIUM,
            74 to ConfidenceBand.MEDIUM, 75 to ConfidenceBand.HIGH, 89 to ConfidenceBand.HIGH,
            90 to ConfidenceBand.VERY_HIGH, 100 to ConfidenceBand.VERY_HIGH)) {
            assertEquals(band, ConfidenceAssessment(score, emptySet(), "test").band)
        }
        assertFailsWith<IllegalArgumentException> { ConfidenceAssessment(-1, emptySet(), "") }
        assertFailsWith<IllegalArgumentException> { ConfidenceAssessment(101, emptySet(), "") }
    }
}
