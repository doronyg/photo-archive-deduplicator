package com.doronyg.photoarchivededuplicator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.doronyg.photoarchivededuplicator.Diagnostics
import com.doronyg.photoarchivededuplicator.PathActions
import com.doronyg.photoarchivededuplicator.UiPreferences
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationController
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationJobState
import com.doronyg.photoarchivededuplicator.exif.ExifQueueController
import com.doronyg.photoarchivededuplicator.operations.SelectedDeletionGroup
import com.doronyg.photoarchivededuplicator.recommendation.DeletionCategoryResults
import com.doronyg.photoarchivededuplicator.recommendation.DeletionRecommendationGroup
import com.doronyg.photoarchivededuplicator.recommendation.DeletionRecommendationReport
import com.doronyg.photoarchivededuplicator.recommendation.DeletionRecommendationService
import com.doronyg.photoarchivededuplicator.recommendation.DeletionRecommendationSummary
import com.doronyg.photoarchivededuplicator.recommendation.DeletionReviewFile
import com.doronyg.photoarchivededuplicator.recommendation.FileStatistics
import com.doronyg.photoarchivededuplicator.recommendation.addFile
import com.doronyg.photoarchivededuplicator.retention.RetentionScoreService
import com.doronyg.photoarchivededuplicator.scan.DirectoryRepository
import com.doronyg.photoarchivededuplicator.strings.AppStrings
import kotlinx.coroutines.CancellationException

internal val DELETION_REVIEW_NOTICE = AppStrings.review.notice

internal fun selectedDeletionCategory(report: DeletionRecommendationReport, selectedId: String?): DeletionCategoryResults? =
    report.categories.firstOrNull { it.category.id == selectedId } ?: report.categories.firstOrNull()

internal fun deletionSummaryText(summary: DeletionRecommendationSummary): String =
    AppStrings.review.summary(summary.groupCount, summary.candidates.fileCount,
        formatStatisticsMegabytes(summary.candidates.sizeBytes))

internal fun deletionGroupSummary(group: DeletionRecommendationGroup): DeletionRecommendationSummary =
    DeletionRecommendationSummary(1, group.candidates.fold(FileStatistics()) { total, file ->
        total.addFile(file.advice.file.sizeBytes)
    })

internal fun selectedDeletionGroups(report: DeletionRecommendationReport, keys: Set<String>): List<SelectedDeletionGroup> =
    report.categories.flatMap { result ->
        result.groups.map { SelectedDeletionGroup(result.category, it) }.filter { it.key in keys }
    }

internal fun toggleDeletionGroup(keys: Set<String>, key: String, selected: Boolean): Set<String> =
    if (selected) keys + key else keys - key

internal fun toggleDeletionCategory(
    keys: Set<String>,
    category: DeletionCategoryResults,
    selected: Boolean,
): Set<String> = keys.toMutableSet().apply {
    for (group in category.groups) {
        val key = SelectedDeletionGroup(category.category, group).key
        if (selected) add(key) else remove(key)
    }
}

private data class DeletionLoad(val token: Any, val report: DeletionRecommendationReport? = null, val error: String? = null)
private data class DeletionReviewRow(
    val group: DeletionRecommendationGroup,
    val file: DeletionReviewFile? = null,
    val retained: Boolean = false,
)

@Composable
internal fun DeletionRecommendationsScreen(
    repository: DirectoryRepository,
    retention: RetentionScoreService,
    queue: ExifQueueController,
    pathActions: PathActions,
    preferences: UiPreferences,
    scanning: Boolean,
    modifier: Modifier = Modifier,
    directoryClassifications: DirectoryClassificationController? = null,
    fileOperationsBusy: Boolean,
    onPrepareDryRun: (List<SelectedDeletionGroup>) -> Unit,
) {
    val choices by preferences.state.collectAsState()
    val settings = choices.ollama
    val profile = remember(settings) {
        if (settings.baseUrl.isBlank() && settings.model.isBlank()) Result.success(null)
        else try {
            Result.success(settings.context())
        } catch (failure: IllegalArgumentException) {
            Result.failure(failure)
        }
    }
    val context = profile.getOrNull()
    val configurationError = profile.exceptionOrNull()?.let { AppStrings.review.configurationError(it.message) }
    val service = remember(repository, retention, context) {
        DeletionRecommendationService(repository, retention, classificationContext = context)
    }
    val queueState by queue.state.collectAsState()
    val classificationState = directoryClassifications?.state?.collectAsState()?.value ?: DirectoryClassificationJobState()
    val queueKey = exifQueueRefreshKey(queueState)
    val blocked = scanning || queueState.isRunning
    var refresh by remember { mutableIntStateOf(0) }
    val token = remember(service, profile, queueKey, blocked, refresh, classificationState.revision) { Any() }
    val latestToken by rememberUpdatedState(token)
    val latestScanning by rememberUpdatedState(scanning)
    val load by produceState<DeletionLoad?>(null, token) {
        value = null
        if (!blocked && configurationError == null) {
            try {
                val report = service.load()
                if (latestToken === token && !latestScanning && !queue.state.value.isRunning &&
                    queueKey == exifQueueRefreshKey(queue.state.value)) value = DeletionLoad(token, report)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                Diagnostics.logger.e("DeletionRecommendations", "Could not load review candidates: ${failure.message}")
                if (latestToken === token) value = DeletionLoad(token, error = AppStrings.review.loadError(failure.message))
            }
        }
    }
    // Hide the old snapshot immediately, before a replacement load's effect can run.
    val current = load?.takeIf { !blocked && configurationError == null && it.token === token }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val category = current?.report?.let { selectedDeletionCategory(it, selectedId) }
    var selectedKeys by remember(token) { mutableStateOf(emptySet<String>()) }
    val selectedGroups = remember(current?.report, selectedKeys) {
        current?.report?.let { selectedDeletionGroups(it, selectedKeys) }.orEmpty()
    }
    val categoryKeys = remember(category) {
        category?.let { result ->
            result.groups.mapTo(mutableSetOf()) { SelectedDeletionGroup(result.category, it).key }
        }.orEmpty()
    }
    val categorySelectedCount = remember(categoryKeys, selectedKeys) { categoryKeys.count { it in selectedKeys } }
    var expanded by remember(token, category?.category?.id) { mutableStateOf(emptySet<String>()) }
    val categoryRows = remember(category, expanded) {
        buildList {
            category?.groups?.forEach { group ->
                add(DeletionReviewRow(group))
                if (group.id in expanded) {
                    group.survivors.forEach { add(DeletionReviewRow(group, it, true)) }
                    group.candidates.forEach { add(DeletionReviewRow(group, it)) }
                }
            }
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(category?.category?.id) { listState.scrollToItem(0) }

    Box(modifier) {
        Row(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item(key = "heading") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(AppStrings.recommendationsTab, style = MaterialTheme.typography.titleLarge)
                        Text(DELETION_REVIEW_NOTICE, style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(enabled = !blocked && configurationError == null && current != null,
                            onClick = { refresh++ }) { Text(AppStrings.review.refresh) }
                        Button(
                            enabled = !blocked && !fileOperationsBusy && selectedGroups.isNotEmpty(),
                            onClick = { onPrepareDryRun(selectedGroups) },
                        ) { Text(AppStrings.operations.prepare(selectedGroups.size)) }
                        when {
                            configurationError != null -> Text(configurationError, color = MaterialTheme.colorScheme.error)
                            blocked -> Text(AppStrings.review.waiting)
                            current == null -> Text(AppStrings.review.loading)
                            current.error != null -> Text(current.error, color = MaterialTheme.colorScheme.error)
                            else -> current.report?.let { report ->
                                Text(AppStrings.review.overall(deletionSummaryText(report.summary)), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
                current?.report?.let { report ->
                    item(key = "categories") {
                        DeletionCategoryGrid {
                            report.categories.forEach { result ->
                                key(result.category.id) {
                                    val isSelected = result.category.id == category?.category?.id
                                    Card(
                                        onClick = { selectedId = result.category.id },
                                        modifier = Modifier.semantics { selected = isSelected },
                                        colors = CardDefaults.cardColors(containerColor =
                                            if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
                                    ) {
                                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(result.category.name, style = MaterialTheme.typography.titleMedium)
                                            Text(result.category.description, style = MaterialTheme.typography.bodySmall)
                                            Text(deletionSummaryText(result.summary), style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (category == null) item { Text(AppStrings.review.noCategories) }
                    else {
                        item(key = "category-heading") {
                            Column {
                                Text(category.category.name, style = MaterialTheme.typography.titleMedium)
                                Text(AppStrings.operations.selection(categorySelectedCount))
                                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        enabled = !blocked && !fileOperationsBusy && categorySelectedCount < categoryKeys.size,
                                        onClick = { selectedKeys = toggleDeletionCategory(selectedKeys, category, true) },
                                    ) { Text(AppStrings.operations.selectAll) }
                                    OutlinedButton(
                                        enabled = !blocked && !fileOperationsBusy && categorySelectedCount > 0,
                                        onClick = { selectedKeys = toggleDeletionCategory(selectedKeys, category, false) },
                                    ) { Text(AppStrings.operations.clearSelection) }
                                }
                                Text(AppStrings.operations.categorySelectionNote, style = MaterialTheme.typography.bodySmall)
                                if (category.groups.isEmpty()) Text(AppStrings.review.emptyCategory)
                            }
                        }
                        items(categoryRows, key = { row ->
                            val groupKey = "${category.category.id.length}:${category.category.id}${row.group.id.length}:${row.group.id}"
                            if (row.file == null) "group:$groupKey"
                            else "${if (row.retained) "keeper" else "candidate"}:$groupKey${row.file.advice.file.path}"
                        }) { row ->
                            val group = row.group
                            if (row.file == null) {
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        val selection = SelectedDeletionGroup(category.category, group)
                                        Row {
                                            Checkbox(
                                                checked = selection.key in selectedKeys,
                                                enabled = !blocked && !fileOperationsBusy,
                                                onCheckedChange = { selectedKeys = toggleDeletionGroup(selectedKeys, selection.key, it) },
                                            )
                                            Text(AppStrings.operations.selectGroup, Modifier.padding(top = 12.dp))
                                        }
                                        Text(AppStrings.review.retain(group.survivors.joinToString { it.advice.file.name }),
                                            color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                                        Text(group.survivors.map { it.advice.file.directoryPath }.distinct().joinToString(),
                                            style = MaterialTheme.typography.bodySmall)
                                        Text(deletionSummaryText(deletionGroupSummary(group)), style = MaterialTheme.typography.bodyMedium)
                                        TextButton(onClick = {
                                            expanded = if (group.id in expanded) expanded - group.id else expanded + group.id
                                        }) { Text(if (group.id in expanded) AppStrings.hideDetails else AppStrings.review.showEvidence) }
                                        if (group.id in expanded) {
                                            Text(group.evidenceSummary, style = MaterialTheme.typography.bodySmall)
                                            DuplicateRecommendationSummary(group.recommendation)
                                        }
                                    }
                                }
                            } else {
                                DeletionReviewFileCard(row.file, row.retained, pathActions)
                            }
                        }
                    }
                }
            }
            ResultsScrollbar(listState, Modifier.fillMaxHeight())
        }
    }
}

@Composable
private fun DeletionReviewFileCard(file: DeletionReviewFile, retained: Boolean, pathActions: PathActions) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
        shape = MaterialTheme.shapes.medium,
        color = if (retained) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (retained) AppStrings.review.retainedCopy else AppStrings.review.redundantCopy,
                style = MaterialTheme.typography.labelLarge)
            FilePathLinks(file.advice.file, pathActions)
            Text(AppStrings.fileSize(file.advice.file.sizeBytes, formatBytes(file.advice.file.sizeBytes)),
                style = MaterialTheme.typography.bodySmall)
            file.evidence.forEach { (label, value) ->
                Text(AppStrings.field(label, value), style = MaterialTheme.typography.bodyMedium)
            }
            FileRecommendationLine(file.advice.file.path, file.advice, file.directoryClassification)
            ExifMetadataLine(file.advice.file.path, file.exif)
            RetentionScoreLine(file.advice.file.path, file.retention)
        }
    }
}
