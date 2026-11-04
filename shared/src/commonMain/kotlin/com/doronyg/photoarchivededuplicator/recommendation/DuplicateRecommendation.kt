package com.doronyg.photoarchivededuplicator.recommendation

import com.doronyg.photoarchivededuplicator.scan.FileRecord
import okio.Path

enum class RecommendationAction {
    KEEP_ONE, KEEP_MULTIPLE, REVIEW,
    // Reserved for a future, explicit collection-protection policy. No current rule emits this.
    PROTECTED,
}

enum class EvidenceStrength { INSUFFICIENT, SUPPORTED, STRONG }
enum class FileRecommendationRole { PREFERRED, REDUNDANT_CANDIDATE, PRESERVE, REVIEW }

enum class RecommendationReason {
    METADATA_ONLY,
    INSUFFICIENT_FILES,
    REPEATED_FILE_PATH,
    SIZE_MISMATCH,
    EVIDENCE_UPDATING,
    MISSING_CAPTURE_EVIDENCE,
    STALE_CAPTURE_EVIDENCE,
    INVALID_CAPTURE_EVIDENCE,
    CONFLICTING_CAPTURE_TIMESTAMPS,
    INCOMPATIBLE_CAPTURE_OFFSETS,
    CONFLICTING_CAPTURE_SUBSECONDS,
    MISSING_RETENTION_ASSESSMENT,
    STALE_RETENTION_ASSESSMENT,
    MATCHING_SIZE_AND_CAPTURE,
    SAME_DIRECTORY_COPY_VARIANTS,
    NUMBERED_COPY_SUFFIX,
    BACKUP_TIMESTAMP_SUFFIX,
    UNSUFFIXED_ORIGINAL,
    NO_UNIQUE_ORIGINAL,
    EXIF_MATCHES_DIRECTORY,
    EXIF_CONTRADICTS_DIRECTORY,
    DIRECTORY_EVIDENCE_CONFLICT,
    UNKNOWN_OR_SPECIAL_LOCATION,
    MULTIPLE_RETAINED_LOCATIONS,
}

data class FileRecommendation(
    val file: FileRecord,
    val role: FileRecommendationRole,
    val normalizedFilename: String,
    val reasonCodes: Set<RecommendationReason>,
    val explanation: String,
)

data class DuplicateGroupRecommendation(
    val action: RecommendationAction,
    val evidenceStrength: EvidenceStrength,
    val reasonCodes: Set<RecommendationReason>,
    val explanation: String,
    val files: Map<Path, FileRecommendation>,
) {
    val preferredFile: FileRecord?
        get() = files.values.singleOrNull { it.role == FileRecommendationRole.PREFERRED }?.file
    val redundantCandidates: List<FileRecord>
        get() = files.values.filter { it.role == FileRecommendationRole.REDUNDANT_CANDIDATE }.map { it.file }
    val mustPreserve: List<FileRecord>
        get() = files.values.filter { it.role != FileRecommendationRole.REDUNDANT_CANDIDATE }.map { it.file }
}
