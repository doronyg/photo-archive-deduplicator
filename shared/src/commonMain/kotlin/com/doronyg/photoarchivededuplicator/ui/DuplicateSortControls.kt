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
import com.doronyg.photoarchivededuplicator.strings.AppStrings

@Composable
internal fun DuplicateSortControls(
    rules: List<DuplicateSortRule>,
    enabled: Boolean,
    onChange: ((List<DuplicateSortRule>) -> List<DuplicateSortRule>) -> Unit,
) {
    Text(AppStrings.duplicates.sortPriority, style = MaterialTheme.typography.titleSmall)
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        rules.forEachIndexed { index, rule ->
            val label = when (rule.criterion) {
                DuplicateSortCriterion.NAME -> AppStrings.duplicates.filename
                DuplicateSortCriterion.FILE_SIZE -> AppStrings.duplicates.fileSize
                DuplicateSortCriterion.DUPLICATE_COUNT -> AppStrings.duplicates.copyCount
                DuplicateSortCriterion.EARLIEST_CREATED -> AppStrings.duplicates.creationDate
                DuplicateSortCriterion.RECOMMENDATION -> AppStrings.duplicates.recommendation
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
                            modifier = Modifier.semantics { contentDescription = AppStrings.duplicates.raisePriority(label) },
                            onClick = { onChange { moveDuplicateSortRule(it, rule.criterion, -1) } },
                        ) { Text(AppStrings.duplicates.higherPriority) }
                        TextButton(
                            enabled = enabled && rule.enabled,
                            modifier = Modifier.semantics { contentDescription = AppStrings.duplicates.changeDirection(label) },
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
                        ) { Text(if (rule.direction == SortDirection.ASCENDING) AppStrings.duplicates.ascending else AppStrings.duplicates.descending) }
                        TextButton(
                            enabled = enabled && index < rules.lastIndex,
                            modifier = Modifier.semantics { contentDescription = AppStrings.duplicates.lowerPriority(label) },
                            onClick = { onChange { moveDuplicateSortRule(it, rule.criterion, 1) } },
                        ) { Text(AppStrings.duplicates.lowerPriority) }
                    }
                }
            }
        }
    }
}
