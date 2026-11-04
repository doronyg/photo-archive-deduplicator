package com.doronyg.photoarchivededuplicator.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.doronyg.photoarchivededuplicator.scan.DuplicateSortCriterion
import com.doronyg.photoarchivededuplicator.scan.DuplicateSortRule
import com.doronyg.photoarchivededuplicator.scan.SortDirection
import com.doronyg.photoarchivededuplicator.scan.moveDuplicateSortRule

@Composable
internal fun DuplicateSortControls(
    rules: List<DuplicateSortRule>,
    enabled: Boolean,
    onChange: ((List<DuplicateSortRule>) -> List<DuplicateSortRule>) -> Unit,
) {
    Text("Sort priority: leftmost enabled criterion first", style = MaterialTheme.typography.titleSmall)
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        rules.forEachIndexed { index, rule ->
            val label = when (rule.criterion) {
                DuplicateSortCriterion.NAME -> "Filename"
                DuplicateSortCriterion.FILE_SIZE -> "File size"
                DuplicateSortCriterion.DUPLICATE_COUNT -> "Copy count"
                DuplicateSortCriterion.EARLIEST_CREATED -> "Creation date"
                DuplicateSortCriterion.RECOMMENDATION -> "Recommendation"
            }
            OutlinedCard {
                Column(Modifier.padding(8.dp)) {
                    FilterChip(
                        selected = rule.enabled,
                        enabled = enabled,
                        onClick = {
                            onChange { current ->
                                current.map { if (it.criterion == rule.criterion) it.copy(enabled = !it.enabled) else it }
                            }
                        },
                        label = { Text(label) },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            enabled = enabled && index > 0,
                            modifier = Modifier.semantics { contentDescription = "Move $label to higher priority" },
                            onClick = { onChange { moveDuplicateSortRule(it, rule.criterion, -1) } },
                        ) { Text("<") }
                        TextButton(
                            enabled = enabled && rule.enabled,
                            modifier = Modifier.semantics { contentDescription = "Change $label sort direction" },
                            onClick = {
                                onChange { current ->
                                    current.map {
                                        if (it.criterion != rule.criterion) it
                                        else it.copy(direction = when (it.direction) {
                                            SortDirection.ASCENDING -> SortDirection.DESCENDING
                                            SortDirection.DESCENDING -> SortDirection.ASCENDING
                                        })
                                    }
                                }
                            },
                        ) { Text(if (rule.direction == SortDirection.ASCENDING) "Ascending" else "Descending") }
                        TextButton(
                            enabled = enabled && index < rules.lastIndex,
                            modifier = Modifier.semantics { contentDescription = "Move $label to lower priority" },
                            onClick = { onChange { moveDuplicateSortRule(it, rule.criterion, 1) } },
                        ) { Text(">") }
                    }
                }
            }
        }
    }
    Text(
        "Filename order is case-sensitive (smallest filename for mixed-name groups). Creation uses the earliest known date. " +
            "Recommendation ascending: Keep one, Keep multiple, Review, Protected. Unknown dates/recommendations stay last. All off keeps search order.",
        style = MaterialTheme.typography.bodySmall,
    )
}
