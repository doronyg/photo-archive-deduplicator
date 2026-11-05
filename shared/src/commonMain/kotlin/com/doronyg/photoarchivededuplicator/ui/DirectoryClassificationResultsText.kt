package com.doronyg.photoarchivededuplicator.ui

import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationContext
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationRecord
import com.doronyg.photoarchivededuplicator.strings.AppStrings
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
        appendLine(AppStrings.analysis.savedExportTitle)
        appendLine(AppStrings.analysis.exportScope(currentOnly))
        appendLine(AppStrings.analysis.exportFilter(quoteResultText(query.trim())))
        if (context == null) appendLine(AppStrings.analysis.notConfigured)
        else {
            appendLine(AppStrings.analysis.exportCurrentServer(quoteResultText(context.baseUrl)))
            appendLine(AppStrings.analysis.exportCurrentModel(quoteResultText(context.model)))
            appendLine(AppStrings.analysis.exportCurrentPrompt(quoteResultText(context.promptVersion)))
        }
        appendLine(AppStrings.analysis.exportCount(records.size))
        records.forEach { record ->
            taskContext.ensureActive()
            appendLine()
            appendLine(AppStrings.analysis.exportDirectory(quoteResultText(record.directoryPath.toString())))
            if (record.originalPath != record.directoryPath.toString()) {
                appendLine(AppStrings.analysis.exportOriginalPath(quoteResultText(record.originalPath)))
            }
            appendLine(AppStrings.analysis.exportClassification(record.classification))
            appendLine(AppStrings.analysis.exportTimestamp(formatEpochMillis(record.classifiedAt)))
            appendLine(AppStrings.analysis.exportServer(quoteResultText(record.context.baseUrl)))
            appendLine(AppStrings.analysis.exportModel(quoteResultText(record.context.model)))
            appendLine(AppStrings.analysis.exportPrompt(quoteResultText(record.context.promptVersion)))
            appendLine(AppStrings.analysis.exportMatches(record.context == context))
        }
    }
}
