package com.doronyg.photoarchivededuplicator.operations.history

import okio.Path

internal const val ARCHIVE_MARKER_NAME = ".photo-archive-id.json"
internal const val HISTORY_DIRECTORY_NAME = ".deduplicator-history"

internal data class ArchiveIdentity(val archiveId: String, val registrationToken: String)
internal data class ArchiveBinding(val identity: ArchiveIdentity, val archiveRoot: Path, val binRoot: Path)
internal data class HistoryProblem(val path: String, val message: String)
internal enum class OperationHistoryMoveStatus { NOT_STARTED, PENDING, COMPLETED, FAILED }
internal enum class OperationHistoryRestoreStatus { NOT_STARTED, PENDING, COMPLETED, FAILED }

internal data class OperationHistoryItem(
    val itemId: String,
    val originalRelativePath: String,
    val binRelativePath: String,
    val sizeBytes: Long,
    val modifiedAtMillis: Long?,
    val createdAtMillis: Long?,
    val categoryId: String,
    val explanation: String,
    val retainedRelativePath: String?,
    val moveStatus: OperationHistoryMoveStatus = OperationHistoryMoveStatus.NOT_STARTED,
    val restoreStatus: OperationHistoryRestoreStatus = OperationHistoryRestoreStatus.NOT_STARTED,
    val confirmedInBin: Boolean = false,
    val completedMoves: Long = 0,
    val completedRestores: Long = 0,
    val movedAt: String? = null,
    val restoredAt: String? = null,
    val lastError: String? = null,
)

internal data class OperationHistoryEntry(
    val operationId: String,
    val createdAt: String,
    val archiveId: String,
    val items: List<OperationHistoryItem>,
    val problems: List<String>,
    val incomplete: Boolean,
    /** False means the journal cannot safely establish even its confirmed prefix. */
    val journalValid: Boolean = true,
)

internal data class OperationHistoryStatistics(
    /** Lifetime confirmed events, not unique files or physical bytes reclaimed. */
    val completedMoves: Long = 0,
    val logicalBytesMoved: Long = 0,
    val filesInBin: Long = 0,
    val logicalBytesInBin: Long = 0,
    val completedRestores: Long = 0,
    val permanentlyDeleted: Long = 0,
)

internal data class OperationHistorySnapshot(
    val identity: ArchiveIdentity?,
    val operations: List<OperationHistoryEntry>,
    val problems: List<HistoryProblem>,
    val statistics: OperationHistoryStatistics,
    val complete: Boolean = problems.isEmpty() && operations.none { it.incomplete },
    val readable: Boolean = true,
)

/** Portable, archive-relative metadata only. This does not inspect or follow filesystem links. */
internal fun resolvePortablePath(root: Path, relativePath: String): Path {
    require(root.isAbsolute && root == root.normalized()) { "Root must be absolute and normalized" }
    val segments = relativePath.split('/')
    require(segments.all { segment ->
        segment.isNotEmpty() && segment != "." && segment != ".." &&
            segment.last() != '.' && segment.last() != ' ' &&
            segment.none { it.code < 32 || it in "\\:<>\"|?*" } &&
            segment.substringBefore('.').trimEnd(' ').uppercase() !in reservedNames
    }) { "Unsafe or non-portable archive-relative path: $relativePath" }
    return segments.fold(root) { path, segment -> path / segment }
}

private val reservedNames = setOf("CON", "PRN", "AUX", "NUL", "CLOCK$", "CONIN$", "CONOUT$") +
    (1..9).flatMap { listOf("COM$it", "LPT$it") } +
    listOf("COM¹", "COM²", "COM³", "LPT¹", "LPT²", "LPT³")

internal fun archiveRelativePath(file: Path, root: Path): String {
    require(file.isAbsolute && file == file.normalized())
    val relative = file.relativeTo(root).segments.joinToString("/")
    require(resolvePortablePath(root, relative) == file) { "File is outside the archive" }
    return relative
}
