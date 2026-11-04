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
import com.doronyg.photoarchivededuplicator.recommendation.DuplicateGroupRecommendation
import com.doronyg.photoarchivededuplicator.recommendation.EvidenceStrength
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

internal fun groupRecommendationText(recommendation: DuplicateGroupRecommendation?): String {
    if (recommendation == null) return "Recommendation: Loading stored evidence..."
    val action = when (recommendation.action) {
        RecommendationAction.KEEP_ONE -> "Keep one"
        RecommendationAction.KEEP_MULTIPLE -> "Keep multiple"
        RecommendationAction.REVIEW -> "Review - no automatic action"
        RecommendationAction.PROTECTED -> "Protected"
    }
    val strength = when (recommendation.evidenceStrength) {
        EvidenceStrength.INSUFFICIENT -> "Insufficient evidence"
        EvidenceStrength.SUPPORTED -> "Supported by metadata"
        EvidenceStrength.STRONG -> "Strong metadata evidence"
    }
    return "Recommendation: $action - $strength"
}

internal fun fileRecommendationText(recommendation: FileRecommendation?): String {
    if (recommendation == null) return "Recommendation: Loading stored evidence..."
    val role = when (recommendation.role) {
        FileRecommendationRole.PREFERRED -> "Preferred copy"
        FileRecommendationRole.REDUNDANT_CANDIDATE -> "Likely redundant candidate"
        FileRecommendationRole.PRESERVE -> "Preserve this copy"
        FileRecommendationRole.REVIEW -> "Preserve for review"
    }
    return "Recommendation: $role - ${recommendation.explanation}"
}

@Composable
internal fun DuplicateRecommendationSummary(recommendation: DuplicateGroupRecommendation?) {
    var expanded by remember(recommendation) { mutableStateOf(false) }
    Column {
        Text(groupRecommendationText(recommendation), style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.clickable(enabled = recommendation != null, role = Role.Button) { expanded = !expanded })
        if (recommendation != null) {
            Text(recommendation.explanation, style = MaterialTheme.typography.bodySmall)
            if (expanded) {
                Text("${recommendation.mustPreserve.size} to preserve; ${recommendation.redundantCandidates.size} likely redundant candidates.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Reasons: ${recommendation.reasonCodes.joinToString { it.name }}", style = MaterialTheme.typography.bodySmall)
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
        if (expanded && recommendation != null) {
            Text("Normalized filename: ${recommendation.normalizedFilename}", style = MaterialTheme.typography.bodySmall)
            Text("Reasons: ${recommendation.reasonCodes.joinToString { it.name }}", style = MaterialTheme.typography.bodySmall)
            Text("Naming and metadata evidence only; contents have not been compared. No files are changed.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
