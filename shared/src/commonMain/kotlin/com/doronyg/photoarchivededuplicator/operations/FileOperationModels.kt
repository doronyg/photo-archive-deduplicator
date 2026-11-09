package com.doronyg.photoarchivededuplicator.operations

import com.doronyg.photoarchivededuplicator.classification.DirectoryClassification
import com.doronyg.photoarchivededuplicator.recommendation.DeletionRecommendationCategory
import com.doronyg.photoarchivededuplicator.recommendation.DeletionRecommendationGroup
import com.doronyg.photoarchivededuplicator.recommendation.FileRecommendationRole
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import kotlinx.serialization.json.JsonObject
import okio.Path

internal const val FILE_BIN_NAME = "DeduplicatorGarbageBin"

internal data class SelectedDeletionGroup(
    val category: DeletionRecommendationCategory,
    val group: DeletionRecommendationGroup,
) {
    val key: String get() = "${category.id.length}:${category.id}${group.id}"
}

internal enum class FileOperationIssueCode {
    BIN_NOT_CONFIGURED, INVALID_ARCHIVE_ROOT, ARCHIVE_ROOT_UNAVAILABLE, ARCHIVE_ROOT_IN_BIN,
    EMPTY_SELECTION, SOURCE_OUTSIDE_ROOT, SOURCE_MISSING, SOURCE_CHANGED, SOURCE_NOT_REGULAR,
    SURVIVOR_MISSING, SURVIVOR_CHANGED, SURVIVOR_IN_BIN, SURVIVOR_SELECTED,
    PROTECTED_GROUP, STALE_RECOMMENDATION, DESTINATION_EXISTS, DESTINATION_COLLISION,
    UNSAFE_PATH, FILESYSTEM_ERROR, BIN_ROOT_CHANGED, BIN_NOT_DIRECTORY, BIN_PARENT_UNAVAILABLE,
    BUSY, CONFIGURATION_ERROR, PROPOSAL_ERROR, DIRECTORY_PREPARATION_ERROR, EXECUTION_ERROR,
}

internal data class FileOperationFinding(
    val code: FileOperationIssueCode,
    val path: Path? = null,
    val detail: String? = null,
)

internal class FileOperationException(val finding: FileOperationFinding) : IllegalStateException(finding.code.name)

internal data class FileOperationMember(
    val file: FileRecord,
    val role: FileRecommendationRole,
    val explanation: String,
    val decisionConfidence: Int,
    val retainedReplacementPath: Path?,
    val capturedAt: String?,
    val classification: DirectoryClassification?,
    val evidence: JsonObject,
)

internal data class FileOperationGroup(
    val key: String,
    val categoryId: String,
    val categoryName: String,
    val explanation: String,
    val duplicateConfidence: Int,
    val members: List<FileOperationMember>,
)

internal data class ProposedFileMove(
    val sourcePath: Path,
    val relativePath: Path?,
    val destinationPath: Path?,
    val survivorPath: Path?,
    val sizeBytes: Long,
    val findings: List<FileOperationFinding>,
)

internal enum class FileOperationValidationStatus { READY, NEEDS_ATTENTION }

internal data class FileOperationProposal(
    val id: String,
    val createdAt: Long,
    val validatedAt: Long,
    val archiveRoot: Path?, // Retained only for historical proposals with archive-relative mappings.
    val binRoot: Path,
    val groups: List<FileOperationGroup>,
    val moves: List<ProposedFileMove>,
    val findings: List<FileOperationFinding>,
) {
    val validationStatus: FileOperationValidationStatus
        get() = if (findings.isEmpty() && moves.isNotEmpty() && moves.all { it.findings.isEmpty() })
            FileOperationValidationStatus.READY else FileOperationValidationStatus.NEEDS_ATTENTION
    val candidateBytes: Long
        get() = moves.fold(0L) { total, move ->
            require(move.sizeBytes >= 0 && total <= Long.MAX_VALUE - move.sizeBytes)
            total + move.sizeBytes
        }
}

internal data class FileOperationSummary(
    val id: String,
    val createdAt: Long,
    val archiveRoot: Path?,
    val binRoot: Path,
    val validationStatus: FileOperationValidationStatus,
    val groupCount: Long,
    val candidateCount: Long,
    val candidateBytes: Long,
)

internal data class FileOperationState(
    val selectedGroups: List<SelectedDeletionGroup> = emptyList(),
    val binRoot: Path? = null,
    val registeredBinRoots: List<Path> = emptyList(),
    val history: List<FileOperationSummary> = emptyList(),
    val proposal: FileOperationProposal? = null,
    val busy: Boolean = false,
    val error: FileOperationFinding? = null,
    val directoryPreparation: BinDirectoryPreparationResult? = null,
    val executionProgress: FileExecutionProgress? = null,
    val executionResult: FileExecutionResult? = null,
)
