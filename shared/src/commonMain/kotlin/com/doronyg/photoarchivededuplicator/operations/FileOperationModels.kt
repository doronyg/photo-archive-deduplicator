package com.doronyg.photoarchivededuplicator.operations

import com.doronyg.photoarchivededuplicator.classification.DirectoryClassification
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationContext
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
    val key: String = "${category.id.length}:${category.id}${group.id}"
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

internal class FileOperationException(val finding: FileOperationFinding, val global: Boolean = false) :
    IllegalStateException(finding.code.name)

internal data class FileOperationExecutionValidation(
    val classificationContext: DirectoryClassificationContext?,
    val groupFindings: Map<String, List<FileOperationFinding>> = emptyMap(),
)

internal data class BinDirectoryPreparationReport(
    val preparedProposal: FileOperationProposal,
    val rejected: Map<String, List<FileOperationFinding>>,
)

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
    val findings: List<FileOperationFinding> = emptyList(),
)

internal data class ProposedFileMove(
    val sourcePath: Path,
    val relativePath: Path?,
    val destinationPath: Path?,
    val survivorPath: Path?,
    val sizeBytes: Long,
    val findings: List<FileOperationFinding>,
)

internal enum class FileOperationValidationStatus { READY, PARTIALLY_READY, NEEDS_ATTENTION }

internal data class FileOperationEligibility(
    val moves: List<ProposedFileMove>,
    val rejected: Map<Path, List<FileOperationFinding>>,
)

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
    val executionEligibility: FileOperationEligibility by lazy { eligibleOperationMoves(this) }
    val canExecute: Boolean get() = executionEligibility.moves.isNotEmpty()
    val validationStatus: FileOperationValidationStatus
        get() = when {
            !canExecute -> FileOperationValidationStatus.NEEDS_ATTENTION
            executionEligibility.rejected.isNotEmpty() || groups.any { it.findings.isNotEmpty() } ->
                FileOperationValidationStatus.PARTIALLY_READY
            else -> FileOperationValidationStatus.READY
        }
    val candidateBytes: Long
        get() = moves.fold(0L) { total, move ->
            require(move.sizeBytes >= 0 && total <= Long.MAX_VALUE - move.sizeBytes)
            total + move.sizeBytes
        }
}

private fun eligibleOperationMoves(proposal: FileOperationProposal): FileOperationEligibility {
    if (proposal.findings.isNotEmpty())
        return FileOperationEligibility(emptyList(), proposal.moves.associate { it.sourcePath to proposal.findings })
    val sources = proposal.moves.mapTo(mutableSetOf()) { it.sourcePath }
    val groupsBySource = mutableMapOf<Path, MutableList<String>>()
    val sourcesByGroup = proposal.groups.associate { group ->
        group.key to group.members.map { it.file.path }.filter { it in sources }.distinct().also { paths ->
            paths.forEach { groupsBySource.getOrPut(it) { mutableListOf() } += group.key }
        }
    }
    val errors = mutableMapOf<String, List<FileOperationFinding>>()
    val queue = ArrayDeque<String>()
    fun reject(key: String, findings: List<FileOperationFinding>) {
        if (key !in errors) queue.addLast(key)
        errors[key] = (errors[key].orEmpty() + findings).distinct()
    }
    proposal.groups.filter { it.findings.isNotEmpty() }.forEach { reject(it.key, it.findings) }
    proposal.moves.filter { it.findings.isNotEmpty() }.forEach { move ->
        groupsBySource[move.sourcePath].orEmpty().forEach { reject(it, move.findings) }
    }
    // Shared candidates connect group decisions; shared retained copies alone do not.
    while (queue.isNotEmpty()) {
        val key = queue.removeFirst()
        for (path in sourcesByGroup[key].orEmpty()) {
            for (related in groupsBySource[path].orEmpty()) {
                if (related !in errors) reject(related, errors.getValue(key))
            }
        }
    }
    val rejected = linkedMapOf<Path, List<FileOperationFinding>>()
    val eligible = mutableListOf<ProposedFileMove>()
    for (move in proposal.moves) {
        val problems = (move.findings + groupsBySource[move.sourcePath].orEmpty().flatMap { errors[it].orEmpty() }).distinct()
        if (problems.isEmpty()) eligible += move else rejected[move.sourcePath] = problems
    }
    return FileOperationEligibility(eligible, rejected)
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
    val availableGroups: List<SelectedDeletionGroup> = emptyList(),
    val cancellationRequested: Boolean = false,
)
