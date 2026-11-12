package com.doronyg.photoarchivededuplicator.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.doronyg.photoarchivededuplicator.strings.AppStrings

@Composable
internal fun DuplicateFilterPanel(
    active: Boolean = false,
    compact: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(true) }
    val scroll = rememberScrollState()
    val color = MaterialTheme.colorScheme.onSurface
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (active) AppStrings.duplicates.activeFilters else AppStrings.duplicates.filters,
                Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            IconButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.semantics {
                    contentDescription = if (expanded) AppStrings.duplicates.hideFilters else AppStrings.duplicates.showFilters
                },
            ) {
                Canvas(Modifier.size(20.dp)) {
                    val edgeY = size.height * if (expanded) 0.65f else 0.35f
                    val centerY = size.height - edgeY
                    val center = Offset(size.width * 0.5f, centerY)
                    drawLine(color, Offset(size.width * 0.2f, edgeY), center, 2.dp.toPx(), StrokeCap.Round)
                    drawLine(color, center, Offset(size.width * 0.8f, edgeY), 2.dp.toPx(), StrokeCap.Round)
                }
            }
        }
        if (expanded) Box(Modifier.fillMaxWidth().heightIn(max = if (compact) 320.dp else Dp.Infinity)) {
            Column(
                Modifier.fillMaxWidth().padding(end = 14.dp).verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
            Box(Modifier.matchParentSize()) {
                ResultsScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
        HorizontalDivider()
    }
}
