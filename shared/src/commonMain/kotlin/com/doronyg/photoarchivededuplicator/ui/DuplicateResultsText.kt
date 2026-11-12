package com.doronyg.photoarchivededuplicator.ui

import com.doronyg.photoarchivededuplicator.exif.ExifRecord
import com.doronyg.photoarchivededuplicator.recommendation.DuplicateGroupRecommendation
import com.doronyg.photoarchivededuplicator.recommendation.FileRecommendation
import com.doronyg.photoarchivededuplicator.retention.RetentionPresentation
import com.doronyg.photoarchivededuplicator.retention.formatScoreValue
import com.doronyg.photoarchivededuplicator.scan.DuplicateCandidateFilter
import com.doronyg.photoarchivededuplicator.scan.DuplicateCandidateGroup
import com.doronyg.photoarchivededuplicator.scan.DuplicateSortCriterion
import com.doronyg.photoarchivededuplicator.scan.DuplicateSortRule
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import com.doronyg.photoarchivededuplicator.strings.AppStrings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.Path

/** Formats the displayed snapshot without querying the inventory or inspecting files. */
internal suspend fun formatDuplicateResults(
    root: String,
    filter: DuplicateCandidateFilter,
    rules: List<DuplicateSortRule>,
    groups: List<DuplicateCandidateGroup>,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    retention: Map<Path, RetentionPresentation> = emptyMap(),
    exif: Map<Path, ExifRecord> = emptyMap(),
    recommendations: Map<Path, DuplicateGroupRecommendation> = emptyMap(),
): String = withContext(dispatcher) {
    val context = currentCoroutineContext()
    buildString {
        appendLine(AppStrings.duplicates.title)
        appendLine(AppStrings.duplicates.exportMatch)
        appendLine(AppStrings.duplicates.exportRoot(quoteResultText(root)))
        appendLine(AppStrings.duplicates.exportType(filter.fileType.name))
        appendLine(AppStrings.duplicates.exportWildcard(quoteResultText(filter.filenamePattern.trim().ifEmpty { "*" })))
        appendDuplicateSortContext(rules)
        appendLine(AppStrings.duplicates.exportGroupCount(groups.size))
        groups.forEachIndexed { index, group ->
            context.ensureActive()
            appendLine()
            appendLine(AppStrings.duplicates.exportGroup(index + 1, quoteResultText(group.name)))
            appendLine(AppStrings.duplicates.exportSize(group.sizeBytes, formatBytes(group.sizeBytes)))
            appendLine(AppStrings.duplicates.exportCopies(group.files.size))
            val recommendation = group.files.firstOrNull()?.let { recommendations[it.path] }
            appendGroupRecommendation(recommendation)
            group.files.forEach { file ->
                appendDuplicateFileResult(file, retention[file.path], exif[file.path], recommendation?.files?.get(file.path))
            }
        }
    }
}

internal fun StringBuilder.appendDuplicateSortContext(rules: List<DuplicateSortRule>) {
    val priority = rules.filter { it.enabled }.joinToString(" > ") { "${it.criterion.name} ${it.direction.name}" }
    appendLine(AppStrings.duplicates.exportSort(priority.ifEmpty { AppStrings.duplicates.searchOrder }))
    if (rules.any { it.enabled && it.criterion == DuplicateSortCriterion.RECOMMENDATION }) {
        appendLine(AppStrings.duplicates.exportRecommendationSort)
    }
    appendLine(AppStrings.duplicates.exportCreationSort)
}

internal fun StringBuilder.appendGroupRecommendation(recommendation: DuplicateGroupRecommendation?) {
    if (recommendation != null) {
        appendLine(AppStrings.duplicates.exportGroupRecommendation(recommendation.action.name))
        appendLine(AppStrings.duplicates.exportExplanation(quoteResultText(recommendation.explanation)))
        appendLine(AppStrings.duplicates.exportReasons(recommendation.reasonCodes.joinToString { it.name }))
        appendLine(groupConfidenceSummaryText(recommendation))
        appendLine(groupConfidenceDetailsText(recommendation))
    }
}

internal suspend fun StringBuilder.appendDuplicateFileResult(
    file: FileRecord,
    assessment: RetentionPresentation?,
    exif: ExifRecord?,
    advice: FileRecommendation?,
) {
    val context = currentCoroutineContext()
    context.ensureActive()
    appendLine(AppStrings.duplicates.exportPath(quoteResultText(file.path.toString())))
    appendLine(AppStrings.duplicates.exportCreated(file.createdAt?.let { AppStrings.utc(formatEpochMillis(it)) } ?: AppStrings.unknown))
    appendLine(AppStrings.duplicates.exportModified(file.modifiedAt?.let { AppStrings.utc(formatEpochMillis(it)) } ?: AppStrings.unknown))
    if (advice != null) {
        appendLine(AppStrings.duplicates.exportFileRecommendation(advice.role.name))
        appendLine("  ${AppStrings.duplicates.exportExplanation(quoteResultText(advice.explanation))}")
        appendLine("  ${AppStrings.duplicates.normalizedFilename(quoteResultText(advice.normalizedFilename))}")
        appendLine("  ${AppStrings.duplicates.exportReasons(advice.reasonCodes.joinToString { it.name })}")
        appendLine("  ${fileDecisionConfidenceSummaryText(advice)}")
        fileDecisionConfidenceDetailsText(advice).lineSequence().forEach { appendLine("  $it") }
    }
    val metadata = presentExif(exif)
    appendLine("  ${quoteResultText(metadata.summary)}")
    metadata.details.forEach { (label, value) -> appendLine("    ${AppStrings.field(label, quoteResultText(value))}") }
    val score = assessment?.score
    if (score == null) appendLine("  ${AppStrings.duplicates.retention(assessment?.label ?: AppStrings.duplicates.notScored)}")
    else {
        appendLine("  ${AppStrings.duplicates.retention("${assessment.scoreText} - ${assessment.label}")}")
        appendLine(AppStrings.duplicates.exportRetentionExplanation(quoteResultText(assessment.mainReason)))
        appendLine(AppStrings.duplicates.exportAlgorithm(score.identity.algorithmId, score.identity.algorithmVersion))
        appendLine(AppStrings.duplicates.exportAssessed(formatEpochMillis(score.createdAt)))
        score.factors.forEach { factor ->
            context.ensureActive()
            val value = formatScoreValue(factor.contribution)
            appendLine("    ${AppStrings.duplicates.contribution("${if (factor.contribution > 0) "+" else ""}$value", quoteResultText(factor.explanation))}")
        }
    }
}

internal fun quoteResultText(value: String): String = buildString {
    append('"')
    value.forEach {
        when (it) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (it.code < 32) append("\\u${it.code.toString(16).padStart(4, '0')}") else append(it)
        }
    }
    append('"')
}
