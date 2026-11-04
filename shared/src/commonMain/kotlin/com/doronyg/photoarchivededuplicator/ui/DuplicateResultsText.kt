package com.doronyg.photoarchivededuplicator.ui

import com.doronyg.photoarchivededuplicator.scan.DuplicateCandidateFilter
import com.doronyg.photoarchivededuplicator.scan.DuplicateCandidateGroup
import com.doronyg.photoarchivededuplicator.scan.DuplicateSortRule
import com.doronyg.photoarchivededuplicator.scan.DuplicateSortCriterion
import com.doronyg.photoarchivededuplicator.retention.RetentionPresentation
import com.doronyg.photoarchivededuplicator.retention.formatScoreValue
import com.doronyg.photoarchivededuplicator.exif.ExifRecord
import com.doronyg.photoarchivededuplicator.recommendation.DuplicateGroupRecommendation
import com.doronyg.photoarchivededuplicator.recommendation.FileRecommendation
import com.doronyg.photoarchivededuplicator.scan.FileRecord
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
        appendLine("Duplicate candidates - not verified duplicates")
        appendLine("Matched by exact case-sensitive filename and byte size; contents have not been compared.")
        appendLine("Stored inventory snapshot. No files have been changed.")
        appendLine("Retention scores are advisory location/metadata heuristics, not probabilities or deletion instructions.")
        appendLine("Root: ${quoteResultText(root)}")
        appendLine("Type: ${filter.fileType.name}")
        appendLine("Wildcard (case-insensitive): ${quoteResultText(filter.filenamePattern.trim().ifEmpty { "*" })}")
        appendDuplicateSortContext(rules)
        appendLine("Candidate groups: ${groups.size}")
        groups.forEachIndexed { index, group ->
            context.ensureActive()
            appendLine()
            appendLine("Group ${index + 1}: ${quoteResultText(group.name)}")
            appendLine("Size per file: ${group.sizeBytes} bytes (${formatBytes(group.sizeBytes)})")
            appendLine("Copies: ${group.files.size}")
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
    appendLine("Sort priority: ${priority.ifEmpty { "none (search order)" }}")
    if (rules.any { it.enabled && it.criterion == DuplicateSortCriterion.RECOMMENDATION }) {
        appendLine("Recommendation ascending: KEEP_ONE, KEEP_MULTIPLE, REVIEW, PROTECTED; unavailable results stay last.")
    }
    appendLine("Creation sorting uses the earliest known date per group; unknown dates sort last.")
}

internal fun StringBuilder.appendGroupRecommendation(recommendation: DuplicateGroupRecommendation?) {
    if (recommendation != null) {
        appendLine("Group recommendation: ${recommendation.action.name}")
        appendLine("Recommendation explanation: ${quoteResultText(recommendation.explanation)}")
        appendLine("Recommendation reasons: ${recommendation.reasonCodes.joinToString { it.name }}")
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
    appendLine("  Path: ${quoteResultText(file.path.toString())}")
    appendLine("  Created: ${file.createdAt?.let { "${formatEpochMillis(it)} UTC" } ?: "unknown"}")
    appendLine("  Modified: ${file.modifiedAt?.let { "${formatEpochMillis(it)} UTC" } ?: "unknown"}")
    if (advice != null) {
        appendLine("  File recommendation: ${advice.role.name}")
        appendLine("  Recommendation explanation: ${quoteResultText(advice.explanation)}")
        appendLine("  Normalized filename: ${quoteResultText(advice.normalizedFilename)}")
        appendLine("  Recommendation reasons: ${advice.reasonCodes.joinToString { it.name }}")
        appendLine("  ${fileDecisionConfidenceSummaryText(advice)}")
        fileDecisionConfidenceDetailsText(advice).lineSequence().forEach { appendLine("  $it") }
    }
    val metadata = presentExif(exif)
    appendLine("  ${quoteResultText(metadata.summary)}")
    metadata.details.forEach { (label, value) -> appendLine("    $label: ${quoteResultText(value)}") }
    val score = assessment?.score
    if (score == null) appendLine("  Retention: ${assessment?.label ?: "Not scored"}")
    else {
        appendLine("  Retention: ${assessment.scoreText} - ${assessment.label}")
        appendLine("  Retention explanation: ${quoteResultText(assessment.mainReason)}")
        appendLine("  Score algorithm: ${score.identity.algorithmId} v${score.identity.algorithmVersion}")
        appendLine("  Assessed: ${formatEpochMillis(score.createdAt)} UTC")
        score.factors.forEach { factor ->
            context.ensureActive()
            val value = formatScoreValue(factor.contribution)
            appendLine("    ${if (factor.contribution > 0) "+" else ""}$value: ${quoteResultText(factor.explanation)}")
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
