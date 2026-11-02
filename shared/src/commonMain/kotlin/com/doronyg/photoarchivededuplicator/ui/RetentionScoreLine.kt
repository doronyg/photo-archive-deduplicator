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
import okio.Path

@Composable
internal fun RetentionScoreLine(path: Path, presentation: RetentionPresentation?) {
    val score = presentation?.score
    var expanded by remember(path, score) { mutableStateOf(false) }
    val label = presentation?.label ?: "Not scored"
    Column {
        Text(
            if (score == null) "Retention: $label"
            else "Retention ${presentation.scoreText} · $label · ${presentation.mainReason}",
            style = MaterialTheme.typography.bodySmall,
            color = if (presentation?.rank == RetentionRank.HIGHEST) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable(enabled = score != null, role = Role.Button) { expanded = !expanded },
        )
        if (expanded && score != null) {
            Text("Advisory only; contents have not been compared.", style = MaterialTheme.typography.bodySmall)
            score.factors.forEach { factor ->
                val value = formatScoreValue(factor.contribution)
                Text("${if (factor.contribution > 0) "+" else ""}$value: ${factor.explanation}",
                    style = MaterialTheme.typography.bodySmall)
            }
            Text("${score.identity.algorithmId} v${score.identity.algorithmVersion} · assessed ${formatEpochMillis(score.createdAt)} UTC",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
