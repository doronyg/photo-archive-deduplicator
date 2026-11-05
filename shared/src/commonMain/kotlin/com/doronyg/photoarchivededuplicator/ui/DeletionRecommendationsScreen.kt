package com.doronyg.photoarchivededuplicator.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import com.doronyg.photoarchivededuplicator.Diagnostics
import com.doronyg.photoarchivededuplicator.PathActions
import com.doronyg.photoarchivededuplicator.UiPreferences
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationController
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationJobState
import com.doronyg.photoarchivededuplicator.exif.ExifQueueController
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
import kotlinx.coroutines.CancellationException

internal const val DELETION_REVIEW_NOTICE =
    "Review only. Proposed file sizes are not verified reclaimable space. No files are changed."

internal fun selectedDeletionCategory(report: DeletionRecommendationReport, selectedId: String?): DeletionCategoryResults? =
    report.categories.firstOrNull { it.category.id == selectedId } ?: report.categories.firstOrNull()

internal fun deletionSummaryText(summary: DeletionRecommendationSummary): String =
    "${summary.groupCount} groups | ${summary.candidates.fileCount} proposed files | " +
        formatStatisticsMegabytes(summary.candidates.sizeBytes)

internal fun deletionGroupSummary(group: DeletionRecommendationGroup): DeletionRecommendationSummary =
    DeletionRecommendationSummary(1, group.candidates.fold(FileStatistics()) { total, file ->
        total.addFile(file.advice.file.sizeBytes)
    })

private data class DeletionLoad(val token: Any, val report: DeletionRecommendationReport? = null, val error: String? = null)

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
    val configurationError = profile.exceptionOrNull()?.let { "Invalid saved Ollama settings: ${it.message}" }
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
                if (latestToken === token) value = DeletionLoad(token, error = "Could not load review candidates: ${failure.message}")
            }
        }
    }
    // Hide the old snapshot immediately, before a replacement load's effect can run.
    val current = load?.takeIf { !blocked && configurationError == null && it.token === token }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val category = current?.report?.let { selectedDeletionCategory(it, selectedId) }
    var expanded by remember(token, category?.category?.id) { mutableStateOf(emptySet<String>()) }
    val listState = rememberLazyListState()
    LaunchedEffect(category?.category?.id) { listState.scrollToItem(0) }

    Box(modifier) {
        Row(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item(key = "heading") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Deletion Recommendations", style = MaterialTheme.typography.titleLarge)
                        Text(DELETION_REVIEW_NOTICE, style = MaterialTheme.typography.bodyMedium)
                        Text("All stored roots, respecting exclusions. Uses current saved EXIF, retention scores and " +
                            "Ollama labels; never extracts metadata, rescores files or contacts Ollama.",
                            style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(enabled = !blocked && configurationError == null && current != null,
                            onClick = { refresh++ }) { Text("Refresh candidates") }
                        when {
                            configurationError != null -> Text(configurationError, color = MaterialTheme.colorScheme.error)
                            blocked -> Text("Review candidates wait until scanning and EXIF processing stop.")
                            current == null -> Text("Loading review candidates from stored evidence...")
                            current.error != null -> Text(current.error, color = MaterialTheme.colorScheme.error)
                            else -> current.report?.let { report ->
                                Text("Overall: ${deletionSummaryText(report.summary)}", style = MaterialTheme.typography.titleMedium)
                                Text("Totals count distinct groups and proposed files across categories.",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                current?.report?.let { report ->
                    item(key = "categories") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(report.categories, key = { it.category.id }) { result ->
                                val isSelected = result.category.id == category?.category?.id
                                Card(
                                    onClick = { selectedId = result.category.id },
                                    modifier = Modifier.width(320.dp).semantics { selected = isSelected },
                                    colors = CardDefaults.cardColors(containerColor =
                                        if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
                                    border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                                ) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(result.category.name, style = MaterialTheme.typography.titleMedium)
                                        Text(result.category.description, style = MaterialTheme.typography.bodySmall)
                                        Text(deletionSummaryText(result.summary), style = MaterialTheme.typography.bodyMedium)
                                        Text(if (isSelected) "Selected category" else "Select to review",
                                            style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                    if (category == null) item { Text("No recommendation categories are registered.") }
                    else {
                        item(key = "category-heading") {
                            Column {
                                Text(category.category.name, style = MaterialTheme.typography.titleMedium)
                                Text(category.category.description, style = MaterialTheme.typography.bodySmall)
                                if (category.groups.isEmpty()) Text("No eligible groups in this category. " +
                                    "Missing, stale or insufficient evidence is never promoted into a recommendation.")
                            }
                        }
                        category.groups.forEach { group ->
                            val groupKey = "${category.category.id.length}:${category.category.id}${group.id.length}:${group.id}"
                            item(key = "group:$groupKey") {
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("Retain: ${group.survivors.joinToString { it.advice.file.name }}",
                                            color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                                        Text(group.survivors.map { it.advice.file.directoryPath }.distinct().joinToString(),
                                            style = MaterialTheme.typography.bodySmall)
                                        Text(deletionSummaryText(deletionGroupSummary(group)), style = MaterialTheme.typography.bodyMedium)
                                        Text(group.evidenceSummary, style = MaterialTheme.typography.bodySmall)
                                        TextButton(onClick = {
                                            expanded = if (group.id in expanded) expanded - group.id else expanded + group.id
                                        }) { Text(if (group.id in expanded) "Collapse details" else "Review files and evidence") }
                                        if (group.id in expanded) {
                                            Text(group.recommendation.explanation, style = MaterialTheme.typography.bodyMedium)
                                            DuplicateRecommendationSummary(group.recommendation)
                                        }
                                    }
                                }
                            }
                            if (group.id in expanded) {
                                items(group.survivors, key = { "keeper:$groupKey${it.advice.file.path}" }) {
                                    DeletionReviewFileCard(it, true, pathActions)
                                }
                                items(group.candidates, key = { "candidate:$groupKey${it.advice.file.path}" }) {
                                    DeletionReviewFileCard(it, false, pathActions)
                                }
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
            Text(if (retained) "RETAIN THIS COPY" else "PROPOSED REDUNDANT COPY - REVIEW ONLY",
                style = MaterialTheme.typography.labelLarge)
            FilePathLinks(file.advice.file, pathActions)
            Text("${file.advice.file.sizeBytes} bytes (${formatBytes(file.advice.file.sizeBytes)})",
                style = MaterialTheme.typography.bodySmall)
            file.evidence.forEach { (label, value) ->
                Text("$label: $value", style = MaterialTheme.typography.bodyMedium)
            }
            FileRecommendationLine(file.advice.file.path, file.advice, file.directoryClassification)
            ExifMetadataLine(file.advice.file.path, file.exif)
            RetentionScoreLine(file.advice.file.path, file.retention)
        }
    }
}
