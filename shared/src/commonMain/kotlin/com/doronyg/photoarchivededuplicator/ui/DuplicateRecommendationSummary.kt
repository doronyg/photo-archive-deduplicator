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
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationRecord
import com.doronyg.photoarchivededuplicator.exif.ExifRecord
import com.doronyg.photoarchivededuplicator.recommendation.ConfidenceAssessment
import com.doronyg.photoarchivededuplicator.recommendation.DuplicateGroupRecommendation
import com.doronyg.photoarchivededuplicator.recommendation.FileRecommendation
import com.doronyg.photoarchivededuplicator.recommendation.FileRecommendationRole
import com.doronyg.photoarchivededuplicator.recommendation.RecommendationAction
import com.doronyg.photoarchivededuplicator.recommendation.recommendDuplicateGroups
import com.doronyg.photoarchivededuplicator.retention.RetentionPresentation
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import com.doronyg.photoarchivededuplicator.strings.AppStrings
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
    directoryClassifications: Map<Path, DirectoryClassificationRecord> = emptyMap(),
): Map<Path, DuplicateGroupRecommendation>? {
    val token = remember(groups, exif, retention, evidenceReady, directoryClassifications) { Any() }
    val snapshot by produceState<RecommendationSnapshot?>(null, token) {
        value = null
        value = RecommendationSnapshot(token, recommendDuplicateGroups(groups, exif, retention, evidenceReady,
            directoryClassifications = directoryClassifications))
    }
    return snapshot?.takeIf { it.token === token }?.byFile
}

internal fun recommendationActionLabel(action: RecommendationAction): String =
    when (action) {
        RecommendationAction.KEEP_ONE -> AppStrings.duplicates.keepOne
        RecommendationAction.KEEP_MULTIPLE -> AppStrings.duplicates.keepMultiple
        RecommendationAction.REVIEW -> AppStrings.duplicates.review
        RecommendationAction.PROTECTED -> AppStrings.duplicates.protected
    }

internal fun groupRecommendationText(recommendation: DuplicateGroupRecommendation?): String {
    if (recommendation == null) return AppStrings.duplicates.loadingRecommendation
    return recommendationActionLabel(recommendation.action)
}

private fun confidenceAssessmentText(assessment: ConfidenceAssessment): String =
    AppStrings.duplicates.confidence(assessment.score,
        assessment.band.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() })

internal fun groupConfidenceSummaryText(recommendation: DuplicateGroupRecommendation): String =
    AppStrings.duplicates.duplicateConfidence(confidenceAssessmentText(recommendation.duplicateConfidence.assessment))

internal fun groupConfidenceDetailsText(recommendation: DuplicateGroupRecommendation): String = buildString {
    val duplicate = recommendation.duplicateConfidence
    appendLine(quoteResultText(duplicate.assessment.explanation))
    appendLine(AppStrings.duplicates.reference(duplicate.referenceFile?.path?.toString()?.let(::quoteResultText) ?: AppStrings.unavailable))
    if (duplicate.comparisons.isEmpty()) appendLine(AppStrings.duplicates.noComparisons)
    duplicate.comparisons.values.forEach { comparison ->
        appendLine(AppStrings.duplicates.comparison(quoteResultText(comparison.file.path.toString())))
        appendLine(AppStrings.duplicates.reference(quoteResultText(comparison.reference.path.toString())))
        appendLine(AppStrings.duplicates.duplicateConfidence(confidenceAssessmentText(comparison.assessment)))
        appendLine(AppStrings.duplicates.contradictions(comparison.hasContradictions))
        appendLine(quoteResultText(comparison.assessment.explanation))
        comparison.evidence.forEach { evidence ->
            val points = "${if (evidence.points > 0) "+" else ""}${evidence.points}"
            appendLine(AppStrings.duplicates.confidenceEvidence(points, quoteResultText(evidence.explanation)))
        }
    }
}.trimEnd()

internal fun fileDecisionConfidenceSummaryText(recommendation: FileRecommendation): String =
    AppStrings.duplicates.retentionConfidence(confidenceAssessmentText(recommendation.retentionDecisionConfidence))

private fun retainedReplacementText(recommendation: FileRecommendation): String =
    AppStrings.duplicates.replacement(recommendation.retainedReplacementPath?.toString()?.let(::quoteResultText) ?: AppStrings.none)

internal fun fileDecisionConfidenceDetailsText(recommendation: FileRecommendation): String = buildString {
    val confidence = recommendation.retentionDecisionConfidence
    appendLine(quoteResultText(recommendation.explanation))
    appendLine(quoteResultText(confidence.explanation))
    append(retainedReplacementText(recommendation))
}

internal fun fileRecommendationText(recommendation: FileRecommendation?): String {
    if (recommendation == null) return AppStrings.duplicates.loadingRecommendation
    return when (recommendation.role) {
        FileRecommendationRole.PREFERRED -> AppStrings.duplicates.preferredCopy
        FileRecommendationRole.REDUNDANT_CANDIDATE -> AppStrings.duplicates.redundantCopy
        FileRecommendationRole.PRESERVE -> AppStrings.duplicates.preserveCopy
        FileRecommendationRole.REVIEW -> AppStrings.duplicates.preserveForReview
    }
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
                Text(AppStrings.duplicates.planCounts(recommendation.mustPreserve.size, recommendation.redundantCandidates.size),
                    style = MaterialTheme.typography.bodySmall)
                Text(groupConfidenceDetailsText(recommendation), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun FileRecommendationLine(
    path: Path,
    recommendation: FileRecommendation?,
    directoryClassification: DirectoryClassificationRecord? = null,
) {
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
            directoryClassification?.let {
                Text(directoryClassificationRecordText(it), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (expanded && recommendation != null) {
            Text(AppStrings.duplicates.normalizedFilename(quoteResultText(recommendation.normalizedFilename)), style = MaterialTheme.typography.bodySmall)
            Text(fileDecisionConfidenceDetailsText(recommendation), style = MaterialTheme.typography.bodySmall)
        }
    }
}
