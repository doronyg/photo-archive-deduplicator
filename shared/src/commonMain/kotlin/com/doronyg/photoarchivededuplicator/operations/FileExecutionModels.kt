package com.doronyg.photoarchivededuplicator.operations

import okio.Path

internal enum class FileMoveExecutionStatus { COMPLETED, FAILED, UNCERTAIN, NOT_STARTED }

internal data class FileMoveExecutionResult(
    val move: ProposedFileMove,
    val status: FileMoveExecutionStatus,
    val error: String? = null,
    val postProcessingError: String? = null,
)

internal data class FileExecutionProgress(
    val total: Int,
    val completed: Int = 0,
    val failed: Int = 0,
    val uncertain: Int = 0,
    val currentSource: Path? = null,
    val finished: Boolean = false,
    val logicalBytesMoved: Long = 0,
)

internal data class FileExecutionResult(
    val operationId: String,
    val items: List<FileMoveExecutionResult>,
    val statisticsError: String? = null,
)
