package com.doronyg.photoarchivededuplicator.operations

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import okio.Path

internal class FileExecutionControl {
    private val cancellation = CompletableDeferred<Unit>()
    val isCancellationRequested: Boolean get() = cancellation.isCompleted
    fun requestCancellation() { cancellation.complete(Unit) }
    fun checkCancellation() {
        if (isCancellationRequested) throw CancellationException("File operation cancelled")
    }
}

internal enum class FileExecutionPhase {
    PREPARING, SAVING_INTENTS, MOVING, VERIFYING, SAVING_RESULTS, UPDATING_INVENTORY, FINISHED, CANCELLED,
}

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
    val phase: FileExecutionPhase = FileExecutionPhase.MOVING,
    val verified: Int = completed,
    val elapsedMillis: Long = 0,
    val remaining: Int = total - completed - failed - uncertain,
)

internal data class FileExecutionResult(
    val operationId: String,
    val items: List<FileMoveExecutionResult>,
    val statisticsError: String? = null,
    val cancelled: Boolean = false,
)
