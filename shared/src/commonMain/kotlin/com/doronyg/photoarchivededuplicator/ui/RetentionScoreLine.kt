package com.doronyg.photoarchivededuplicator.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import com.doronyg.photoarchivededuplicator.retention.RetentionPresentation
import com.doronyg.photoarchivededuplicator.retention.RetentionRank
import com.doronyg.photoarchivededuplicator.retention.formatScoreValue
import com.doronyg.photoarchivededuplicator.strings.AppStrings
import okio.Path

@Composable
internal fun RetentionScoreLine(path: Path, presentation: RetentionPresentation?) {
    val score = presentation?.score
    var expanded by remember(path, score) { mutableStateOf(false) }
    val label = presentation?.label ?: AppStrings.duplicates.notScored
    Column {
        Text(
            if (score == null) AppStrings.duplicates.retention(label)
            else AppStrings.duplicates.retentionScore(presentation.scoreText, label, presentation.mainReason),
            style = MaterialTheme.typography.bodySmall,
            color = if (presentation?.rank == RetentionRank.HIGHEST) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable(enabled = score != null, role = Role.Button) { expanded = !expanded },
        )
        if (expanded && score != null) {
            score.factors.forEach { factor ->
                val value = formatScoreValue(factor.contribution)
                Text(AppStrings.duplicates.contribution("${if (factor.contribution > 0) "+" else ""}$value", factor.explanation),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
