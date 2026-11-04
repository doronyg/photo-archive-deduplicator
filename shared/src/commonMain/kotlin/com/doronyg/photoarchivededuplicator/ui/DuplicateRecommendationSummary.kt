package com.doronyg.photoarchivededuplicator.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import com.doronyg.photoarchivededuplicator.exif.ExifRecord
import com.doronyg.photoarchivededuplicator.recommendation.ConfidenceAssessment
import com.doronyg.photoarchivededuplicator.recommendation.DuplicateGroupRecommendation
import com.doronyg.photoarchivededuplicator.recommendation.FileRecommendation
import com.doronyg.photoarchivededuplicator.recommendation.FileRecommendationRole
import com.doronyg.photoarchivededuplicator.recommendation.RecommendationAction
import com.doronyg.photoarchivededuplicator.recommendation.recommendDuplicateGroups
import com.doronyg.photoarchivededuplicator.retention.RetentionPresentation
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import okio.Path

private data class RecommendationSnapshot(
    val token: Any,
    val byFile: Map<Path, DuplicateGroupRecommendation>,
)

@Composable
internal fun rememberDuplicateRecommendations(
    groups: List<List<FileRecord>>,
    exif: Map<Path, ExifRecord>,
    retention: Map<Path, RetentionPresentation>,
    evidenceReady: Boolean,
): Map<Path, DuplicateGroupRecommendation>? {
    val token = remember(groups, exif, retention, evidenceReady) { Any() }
    val snapshot by produceState<RecommendationSnapshot?>(null, token) {
        value = null
        value = RecommendationSnapshot(token, recommendDuplicateGroups(groups, exif, retention, evidenceReady))
    }
    return snapshot?.takeIf { it.token === token }?.byFile
}

internal fun recommendationActionLabel(action: RecommendationAction): String =
    when (action) {
        RecommendationAction.KEEP_ONE -> "Keep one"
        RecommendationAction.KEEP_MULTIPLE -> "Keep multiple"
        RecommendationAction.REVIEW -> "Review - no automatic action"
        RecommendationAction.PROTECTED -> "Protected"
    }

internal fun groupRecommendationText(recommendation: DuplicateGroupRecommendation?): String {
    if (recommendation == null) return "Recommendation: Loading stored evidence..."
    val action = recommendationActionLabel(recommendation.action)
    return "Recommendation: $action"
}

private fun confidenceAssessmentText(assessment: ConfidenceAssessment): String =
    "${assessment.score}/100 (${assessment.band.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }})"

internal fun groupConfidenceSummaryText(recommendation: DuplicateGroupRecommendation): String =
    "Duplicate confidence: ${confidenceAssessmentText(recommendation.duplicateConfidence.assessment)}"

internal fun groupConfidenceDetailsText(recommendation: DuplicateGroupRecommendation): String = buildString {
    val duplicate = recommendation.duplicateConfidence
    appendLine("Heuristic scores, not probabilities; even 100/100 is not content-verified.")
    appendLine("Duplicate-confidence explanation: ${quoteResultText(duplicate.assessment.explanation)}")
    appendLine("Duplicate-confidence reasons: ${duplicate.assessment.reasonCodes.joinToString { it.name }}")
    appendLine("Duplicate-confidence reference: ${duplicate.referenceFile?.path?.toString()?.let(::quoteResultText) ?: "unavailable"}")
    if (duplicate.comparisons.isEmpty()) appendLine("Duplicate-confidence comparisons: none")
    duplicate.comparisons.values.forEach { comparison ->
        appendLine("  Comparison: ${quoteResultText(comparison.file.path.toString())}")
        appendLine("    Reference: ${quoteResultText(comparison.reference.path.toString())}")
        appendLine("    Duplicate confidence: ${confidenceAssessmentText(comparison.assessment)}")
        appendLine("    Contradictions: ${if (comparison.hasContradictions) "yes" else "no"}")
        appendLine("    Comparison explanation: ${quoteResultText(comparison.assessment.explanation)}")
        appendLine("    Comparison reasons: ${comparison.assessment.reasonCodes.joinToString { it.name }}")
        comparison.evidence.forEach { evidence ->
            val points = "${if (evidence.points > 0) "+" else ""}${evidence.points}"
            appendLine("      $points points [${evidence.reasonCode.name}]: ${quoteResultText(evidence.explanation)}")
        }
    }
}.trimEnd()

internal fun fileDecisionConfidenceSummaryText(recommendation: FileRecommendation): String =
    "Retention-decision confidence: ${confidenceAssessmentText(recommendation.retentionDecisionConfidence)}"

private fun retainedReplacementText(recommendation: FileRecommendation): String =
    "Retained replacement: ${recommendation.retainedReplacementPath?.toString()?.let(::quoteResultText) ?: "none"}"

internal fun fileDecisionConfidenceDetailsText(recommendation: FileRecommendation): String = buildString {
    val confidence = recommendation.retentionDecisionConfidence
    appendLine("Retention-confidence explanation: ${quoteResultText(confidence.explanation)}")
    appendLine("Retention-confidence reasons: ${confidence.reasonCodes.joinToString { it.name }}")
    appendLine(retainedReplacementText(recommendation))
    append("Heuristic scores, not probabilities; even 100/100 is not content-verified.")
}

internal fun fileRecommendationText(recommendation: FileRecommendation?): String {
    if (recommendation == null) return "Recommendation: Loading stored evidence..."
    val role = when (recommendation.role) {
        FileRecommendationRole.PREFERRED -> "Preferred copy"
        FileRecommendationRole.REDUNDANT_CANDIDATE -> "Likely redundant candidate"
        FileRecommendationRole.PRESERVE -> "Preserve this copy"
        FileRecommendationRole.REVIEW -> "Preserve for review"
    }
    return "Recommendation: $role - ${quoteResultText(recommendation.explanation)}"
}

@Composable
internal fun DuplicateRecommendationSummary(recommendation: DuplicateGroupRecommendation?) {
    var expanded by remember(recommendation) { mutableStateOf(false) }
    Column {
        Text(groupRecommendationText(recommendation), style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.clickable(enabled = recommendation != null, role = Role.Button) { expanded = !expanded })
        if (recommendation != null) {
            Text(groupConfidenceSummaryText(recommendation), style = MaterialTheme.typography.bodySmall)
            if (expanded) {
                Text(quoteResultText(recommendation.explanation), style = MaterialTheme.typography.bodySmall)
                Text("${recommendation.mustPreserve.size} to preserve; ${recommendation.redundantCandidates.size} likely redundant candidates.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Reasons: ${recommendation.reasonCodes.joinToString { it.name }}", style = MaterialTheme.typography.bodySmall)
                Text(groupConfidenceDetailsText(recommendation), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun FileRecommendationLine(path: Path, recommendation: FileRecommendation?) {
    var expanded by remember(path, recommendation) { mutableStateOf(false) }
    Column {
        Text(fileRecommendationText(recommendation), style = MaterialTheme.typography.bodySmall,
            color = if (recommendation?.role == FileRecommendationRole.PREFERRED) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable(enabled = recommendation != null, role = Role.Button) { expanded = !expanded })
        if (recommendation != null) {
            Text(fileDecisionConfidenceSummaryText(recommendation), style = MaterialTheme.typography.bodySmall)
            if (recommendation.role == FileRecommendationRole.REDUNDANT_CANDIDATE) {
                Text(retainedReplacementText(recommendation), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (expanded && recommendation != null) {
            Text("Normalized filename: ${quoteResultText(recommendation.normalizedFilename)}", style = MaterialTheme.typography.bodySmall)
            Text("Reasons: ${recommendation.reasonCodes.joinToString { it.name }}", style = MaterialTheme.typography.bodySmall)
            Text(fileDecisionConfidenceDetailsText(recommendation), style = MaterialTheme.typography.bodySmall)
            Text("Naming and metadata evidence only; contents have not been compared. No files are changed.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
