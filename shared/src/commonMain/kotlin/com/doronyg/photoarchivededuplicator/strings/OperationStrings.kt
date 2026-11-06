package com.doronyg.photoarchivededuplicator.strings

import com.doronyg.photoarchivededuplicator.operations.FileOperationFinding
import com.doronyg.photoarchivededuplicator.operations.FileOperationIssueCode
import com.doronyg.photoarchivededuplicator.operations.FileOperationValidationStatus
import com.doronyg.photoarchivededuplicator.recommendation.FileRecommendationRole

internal object OperationStrings {
    const val availability = "AI review and file moves are unavailable in this increment. Dry runs do not change files."
    const val aiStatus = "AI review: Not reviewed"
    const val binRoot = "Bin root (full absolute path)"
    const val binPlaceholder = "Enter a full absolute Bin path"
    const val saveBin = "Save Bin configuration"
    const val configureNote = "Saving configuration does not create directories."
    const val mappingNote = "Destinations preserve the full original source path beneath the Bin, without operation or file IDs."
    const val exclusionsNote = "All registered Bin roots, including previous locations, remain excluded from scanning and recommendations."
    const val registeredRoots = "Registered Bin roots"
    const val createDryRun = "Create dry run"
    const val selectedGroups = "Selected recommendations"
    const val emptySelection = "Select whole groups in Deletion Recommendations to prepare a dry run."
    const val selectGroup = "Select this category recommendation"
    const val history = "Recent proposals"
    const val noHistory = "No saved proposals."
    const val open = "Open saved result"
    const val proposal = "Dry-run result"
    const val source = "Source"
    const val relativePath = "Original relative path"
    const val destination = "Proposed destination"
    const val suggestedOutcome = "Suggested outcome"
    const val survivor = "Retained replacement"
    const val unmapped = "Not mapped"
    const val captureTime = "Captured at"
    const val classification = "Classification"
    const val evidence = "Saved evidence"
    const val waiting = "Waiting for scanning or EXIF analysis to finish."
    const val busy = "Preparing file operation data..."
    const val createdAt = "Created (epoch milliseconds)"
    const val validatedAt = "Validated (epoch milliseconds)"
    const val duplicateConfidence = "Duplicate confidence"
    const val decisionConfidence = "Decision confidence"
    const val fileStays = "File stays"
    const val missingDestination = "Move needs attention: destination not mapped"
    const val showEvidence = "Show evidence"
    const val hideEvidence = "Hide evidence"
    const val explanation = "Explanation"
    const val copyResults = "Copy results"
    const val copying = "Copying..."
    const val copiedResults = "Results copied."
    const val proposalId = "Proposal ID"
    const val modifiedAt = "Modified (epoch milliseconds)"
    const val fileCreatedAt = "File created (epoch milliseconds)"
    const val engineRole = "Original engine role"

    fun fileMoves(destination: String) = "File moves to $destination"
    fun copyResultsError(detail: String?) = "Could not copy results: ${detail ?: "Unknown error"}"
    fun selection(count: Int) = "$count selected groups"
    fun prepare(count: Int) = "Prepare dry run for $count groups"
    fun group(category: String, candidates: Int, members: Int) = "$category | $candidates candidates | $members members"
    fun summary(groups: Long, candidates: Long, size: String) = "$groups groups | $candidates candidates | $size"
    fun confidence(value: Int) = "Decision confidence: $value/100"
    fun status(status: FileOperationValidationStatus) = when (status) {
        FileOperationValidationStatus.READY -> "Dry-run metadata checks: Passed"
        FileOperationValidationStatus.NEEDS_ATTENTION -> "Dry-run metadata checks: Needs attention"
    }
    fun role(role: FileRecommendationRole) = when (role) {
        FileRecommendationRole.PREFERRED -> "Retained member"
        FileRecommendationRole.REDUNDANT_CANDIDATE -> "Candidate"
        FileRecommendationRole.PRESERVE -> "Preserved member"
        FileRecommendationRole.REVIEW -> "Member requiring review"
    }
    fun finding(finding: FileOperationFinding): String = buildString {
        append(reason(finding.code))
        finding.path?.let { append("\n").append(it) }
        finding.detail?.takeIf { it.isNotBlank() }?.let { append("\n").append(it) }
    }
    fun reason(code: FileOperationIssueCode): String = when (code) {
        FileOperationIssueCode.BIN_NOT_CONFIGURED -> "Configure a Bin root first."
        FileOperationIssueCode.INVALID_ARCHIVE_ROOT -> "Enter a valid absolute source archive root."
        FileOperationIssueCode.ARCHIVE_ROOT_UNAVAILABLE -> "The source archive root is unavailable."
        FileOperationIssueCode.ARCHIVE_ROOT_IN_BIN -> "The source archive root cannot be inside a registered Bin."
        FileOperationIssueCode.EMPTY_SELECTION -> "Select at least one recommendation group."
        FileOperationIssueCode.SOURCE_OUTSIDE_ROOT -> "The source file is outside the archive root."
        FileOperationIssueCode.SOURCE_MISSING -> "The source file is missing."
        FileOperationIssueCode.SOURCE_CHANGED -> "The source file has changed since it was scanned."
        FileOperationIssueCode.SOURCE_NOT_REGULAR -> "The source is not a regular file."
        FileOperationIssueCode.SURVIVOR_MISSING -> "The retained replacement is missing."
        FileOperationIssueCode.SURVIVOR_CHANGED -> "The retained replacement has changed since it was scanned."
        FileOperationIssueCode.SURVIVOR_IN_BIN -> "The retained replacement is inside a registered Bin."
        FileOperationIssueCode.SURVIVOR_SELECTED -> "A retained replacement is also selected as a candidate."
        FileOperationIssueCode.PROTECTED_GROUP -> "This group contains protected members."
        FileOperationIssueCode.STALE_RECOMMENDATION -> "The recommendation is no longer current. Refresh recommendations."
        FileOperationIssueCode.DESTINATION_EXISTS -> "The proposed destination already exists."
        FileOperationIssueCode.DESTINATION_COLLISION -> "Multiple candidates have the same proposed destination."
        FileOperationIssueCode.UNSAFE_PATH -> "The path cannot be safely mapped into the Bin."
        FileOperationIssueCode.FILESYSTEM_ERROR -> "The filesystem could not be inspected."
        FileOperationIssueCode.BIN_ROOT_CHANGED -> "The Bin root changed while preparing the dry run."
        FileOperationIssueCode.BIN_NOT_DIRECTORY -> "The configured Bin path is not a directory."
        FileOperationIssueCode.BIN_PARENT_UNAVAILABLE -> "The Bin parent directory is unavailable."
        FileOperationIssueCode.BUSY -> "Wait for the current scan, EXIF analysis, or operation to finish."
        FileOperationIssueCode.CONFIGURATION_ERROR -> "The Bin configuration could not be saved."
        FileOperationIssueCode.PROPOSAL_ERROR -> "The dry-run proposal could not be saved or loaded."
    }
}
