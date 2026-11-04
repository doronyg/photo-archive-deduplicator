package com.doronyg.photoarchivededuplicator.ui

import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationContext
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationRecord
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Formats the complete filtered snapshot, including off-screen rows, without database or network access. */
internal suspend fun formatDirectoryClassificationResults(
    records: List<DirectoryClassificationRecord>,
    context: DirectoryClassificationContext?,
    currentOnly: Boolean,
    query: String,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): String = withContext(dispatcher) {
    val taskContext = currentCoroutineContext()
    buildString {
        appendLine("Saved Ollama directory classifications")
        appendLine("Advisory path-based hints, not content verification or deletion instructions. No files have been changed.")
        appendLine("Scope: ${if (currentOnly) "Current saved settings" else "All saved results"}")
        appendLine("Filter: ${quoteResultText(query.trim())}")
        if (context == null) appendLine("Current settings: not configured")
        else {
            appendLine("Current server: ${quoteResultText(context.baseUrl)}")
            appendLine("Current model: ${quoteResultText(context.model)}")
            appendLine("Current prompt version: ${quoteResultText(context.promptVersion)}")
        }
        appendLine("Results: ${records.size}")
        records.forEach { record ->
            taskContext.ensureActive()
            appendLine()
            appendLine("Directory: ${quoteResultText(record.directoryPath.toString())}")
            if (record.originalPath != record.directoryPath.toString()) {
                appendLine("Original path: ${quoteResultText(record.originalPath)}")
            }
            appendLine("Classification: ${record.classification.name}")
            appendLine("Classified at: ${formatEpochMillis(record.classifiedAt)} UTC")
            appendLine("Server: ${quoteResultText(record.context.baseUrl)}")
            appendLine("Model: ${quoteResultText(record.context.model)}")
            appendLine("Prompt version: ${quoteResultText(record.context.promptVersion)}")
            appendLine("Matches current saved settings: ${record.context == context}")
        }
    }
}
