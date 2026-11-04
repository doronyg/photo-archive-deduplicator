package com.doronyg.photoarchivededuplicator.recommendation

import com.doronyg.photoarchivededuplicator.scan.FileRecord
import okio.Path

enum class ConfidenceBand { VERY_LOW, LOW, MEDIUM, HIGH, VERY_HIGH }

enum class ConfidenceReason {
    AI_PREFERRED, AI_BACKUP, AI_PROTECTED, AI_UNKNOWN,
    EVIDENCE_UNAVAILABLE,
    NORMALIZED_FILENAME_MATCH, NORMALIZED_FILENAME_DIFFERS,
    MODIFIED_TIMESTAMP_MATCH, MODIFIED_TIMESTAMP_DIFFERS,
    CAPTURE_TIMESTAMP_MATCH, CAPTURE_TIMESTAMP_CONFLICT,
    CAMERA_MAKE_MATCH, CAMERA_MAKE_CONFLICT,
    CAMERA_MODEL_MATCH, CAMERA_MODEL_CONFLICT,
    DIMENSIONS_MATCH, DIMENSIONS_CONFLICT,
    CAPTURE_OFFSET_MATCH, CAPTURE_OFFSET_CONFLICT,
    CAPTURE_SUBSECOND_MATCH, CAPTURE_SUBSECOND_CONFLICT,
    ORIENTATION_MATCH, ORIENTATION_CONFLICT,
    CONTRADICTION_CAP, SIZE_NOT_SCORED,
    HIGHEST_CURRENT_RETENTION_REFERENCE, DETERMINISTIC_REFERENCE_FALLBACK,
    WEAKEST_MEMBER,
    SAME_DIRECTORY, HIGHEST_RETENTION_SCORE, EQUAL_RETENTION_SCORES, CLOSE_RETENTION_SCORES,
    MEANINGFUL_RETENTION_ADVANTAGE, SCORE_GAP_WITHOUT_SUPPORT,
    CAPTURE_DATE_MATCHES_DIRECTORY, FILENAME_DATE_MATCHES_DIRECTORY, CONFLICTING_DIRECTORY_DATES,
    MULTIPLE_NONSTANDARD_DIRECTORIES, MULTIPLE_RETAINED_LOCATIONS, UNKNOWN_OR_SPECIAL_LOCATION,
    NO_RELIABLE_LOCATION_PREFERENCE, DUPLICATE_CONFIDENCE_INSUFFICIENT, CONFLICTING_EXIF_METADATA,
    EXPLICIT_PROTECTION, INSUFFICIENT_FILES, SIZE_MISMATCH, REPEATED_FILE_PATH,
    MISSING_RETENTION_ASSESSMENT, STALE_RETENTION_ASSESSMENT, EVIDENCE_UPDATING,
    DESIGNATED_SURVIVOR, COLLECTION_REPRESENTATIVE, RETAINED_REPLACEMENT,
    DIRECT_REPLACEMENT_EVIDENCE, CONTENT_NOT_VERIFIED, RETENTION_PLAN_INVALID,
    EXPENDABLE_BACKUP_LOCATION, BETTER_RETAINED_LOCATION, BACKUP_FALLBACK_COPY,
}

data class ConfidenceAssessment(
    val score: Int,
    val reasonCodes: Set<ConfidenceReason>,
    val explanation: String,
) {
    init {
        require(score in 0..100) { "Confidence score must be within 0..100." }
    }

    val band: ConfidenceBand get() = when {
        score >= 90 -> ConfidenceBand.VERY_HIGH
        score >= 75 -> ConfidenceBand.HIGH
        score >= 50 -> ConfidenceBand.MEDIUM
        score >= 25 -> ConfidenceBand.LOW
        else -> ConfidenceBand.VERY_LOW
    }
}

data class ConfidenceEvidence(
    val reasonCode: ConfidenceReason,
    val points: Int,
    val explanation: String,
)

data class FileDuplicateConfidence(
    val file: FileRecord,
    val reference: FileRecord,
    val assessment: ConfidenceAssessment,
    val evidence: List<ConfidenceEvidence>,
    val hasContradictions: Boolean,
)

data class DuplicateConfidenceAssessment(
    val referenceFile: FileRecord?,
    val assessment: ConfidenceAssessment,
    val comparisons: Map<Path, FileDuplicateConfidence>,
) {
    val score: Int get() = assessment.score
    val band: ConfidenceBand get() = assessment.band
    val hasContradictions: Boolean get() =
        ConfidenceReason.CONFLICTING_EXIF_METADATA in assessment.reasonCodes ||
            comparisons.values.any { it.hasContradictions }
}

data class DuplicateConfidenceConfig(
    val filenameWeight: Int = 30,
    val modifiedWeight: Int = 25,
    val captureWeight: Int = 30,
    val cameraMakeWeight: Int = 5,
    val cameraModelWeight: Int = 5,
    val dimensionsWeight: Int = 5,
    val contradictionCap: Int = 24,
) {
    init {
        val weights = listOf(filenameWeight, modifiedWeight, captureWeight, cameraMakeWeight,
            cameraModelWeight, dimensionsWeight)
        require(weights.all { it >= 0 } && weights.sumOf { it.toLong() } == 100L) {
            "Confidence weights must be nonnegative and total 100."
        }
        require(contradictionCap in 0..24) { "Contradictions must cap confidence below the low band." }
    }
}
