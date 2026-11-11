package com.doronyg.photoarchivededuplicator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassification
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationRecord
import com.doronyg.photoarchivededuplicator.operations.DuplicateDeletionSelection
import com.doronyg.photoarchivededuplicator.operations.duplicateDeletionGroupId
import com.doronyg.photoarchivededuplicator.recommendation.DuplicateGroupRecommendation
import com.doronyg.photoarchivededuplicator.recommendation.FileRecommendationRole
import com.doronyg.photoarchivededuplicator.recommendation.RecommendationAction
import com.doronyg.photoarchivededuplicator.strings.AppStrings
import okio.Path

internal fun duplicateDeletionSelectionKey(selection: DuplicateDeletionSelection): String =
    duplicateDeletionGroupId(selection)

internal fun toggleDuplicateDeletionFile(
    current: DuplicateDeletionSelection?,
    group: DuplicateDeletionSelection,
    path: Path,
    selected: Boolean,
): DuplicateDeletionSelection? {
    require(group.files.any { it.path == path }) { AppStrings.operations.staleDuplicateSelection }
    val paths = current?.selectedPaths.orEmpty().toMutableSet()
    if (selected) paths += path else paths -= path
    require(group.files.any { it.path !in paths }) { AppStrings.operations.keepUnselectedCopy }
    return if (paths.isEmpty()) null else group.copy(selectedPaths = paths)
}

internal fun duplicateDeletionGroupProtected(
    group: DuplicateDeletionSelection,
    recommendation: DuplicateGroupRecommendation?,
    classifications: Map<Path, DirectoryClassificationRecord>,
): Boolean = recommendation?.action == RecommendationAction.PROTECTED ||
    group.files.any { classifications[it.directoryPath]?.classification == DirectoryClassification.PROTECTED }

internal fun duplicateDeletionGroupRecommended(recommendation: DuplicateGroupRecommendation?): Boolean {
    val advice = recommendation?.takeUnless { it.action == RecommendationAction.PROTECTED } ?: return false
    val candidates = advice.files.values.filter { it.role == FileRecommendationRole.REDUNDANT_CANDIDATE }
    return advice.files.isNotEmpty() && candidates.all { candidate ->
        advice.files[candidate.retainedReplacementPath]?.role in
            setOf(FileRecommendationRole.PREFERRED, FileRecommendationRole.PRESERVE, FileRecommendationRole.REVIEW)
    }
}

@Composable
internal fun DuplicateDeletionSelectionBar(
    selections: Collection<DuplicateDeletionSelection>,
    enabled: Boolean,
    onClear: () -> Unit,
    onAdd: () -> Unit,
) {
    val strings = AppStrings.operations
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(strings.duplicateDeletionNote, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = enabled && selections.isNotEmpty(), onClick = onAdd) {
                Text(strings.addDeletionSelections(selections.size))
            }
            OutlinedButton(enabled = enabled && selections.isNotEmpty(), onClick = onClear) {
                Text(strings.clearSelection)
            }
        }
    }
}

@Composable
internal fun DuplicateDeletionCheckbox(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(Modifier.toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)) {
        Checkbox(checked = checked, enabled = enabled, onCheckedChange = null)
        Text(label, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
    }
}
