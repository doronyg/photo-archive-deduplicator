package com.doronyg.photoarchivededuplicator.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.doronyg.photoarchivededuplicator.Diagnostics
import com.doronyg.photoarchivededuplicator.UiPreferences
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationContext
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationController
import com.doronyg.photoarchivededuplicator.exif.ExifQueueController
import com.doronyg.photoarchivededuplicator.operations.OperationHistoryController
import com.doronyg.photoarchivededuplicator.recommendation.InventoryFileCounts
import com.doronyg.photoarchivededuplicator.recommendation.StatisticsFileType
import com.doronyg.photoarchivededuplicator.retention.RetentionScoreService
import com.doronyg.photoarchivededuplicator.scan.DirectoryRepository
import com.doronyg.photoarchivededuplicator.strings.AppStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import okio.Path

private data class CleanupResultsLoad(
    val token: Any,
    val snapshot: CleanupResultsSnapshot? = null,
    val binRoot: Path? = null,
    val error: String? = null,
)

@Composable
internal fun CleanupResultsScreen(
    repository: DirectoryRepository,
    retention: RetentionScoreService,
    queue: ExifQueueController,
    preferences: UiPreferences,
    operationHistory: OperationHistoryController,
    directoryClassifications: DirectoryClassificationController,
    scanning: Boolean,
    fileOperationsBusy: Boolean,
    onDetailedStatistics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val choices by preferences.state.collectAsState()
    val history by operationHistory.state.collectAsState()
    val classification by directoryClassifications.state.collectAsState()
    val exif by queue.state.collectAsState()
    val profile = remember(choices.ollama) {
        if (choices.ollama.baseUrl.isBlank() && choices.ollama.model.isBlank())
            Result.success<DirectoryClassificationContext?>(null)
        else try {
            Result.success(choices.ollama.context())
        } catch (failure: IllegalArgumentException) {
            Result.failure(failure)
        }
    }
    val service = remember(repository, retention, profile) {
        CleanupResultsService(repository, retention, classificationContext = profile.getOrNull())
    }
    val blocked = scanning || exif.isRunning || classification.isRunning || fileOperationsBusy
    val queueKey = exifQueueRefreshKey(exif)
    var refresh by remember { mutableIntStateOf(0) }
    val token = remember(service, blocked, queueKey, classification.revision, refresh) { Any() }
    val latestToken by rememberUpdatedState(token)
    val load by produceState<CleanupResultsLoad?>(null, token) {
        value = null
        if (!blocked) {
            val configurationError = profile.exceptionOrNull()
            if (configurationError != null) {
                value = CleanupResultsLoad(token, error = AppStrings.review.configurationError(configurationError.message))
            } else try {
                val bin = repository.binRoot()
                val snapshot = service.load()
                if (latestToken === token) value = CleanupResultsLoad(token, snapshot, bin)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                Diagnostics.logger.e("CleanupResults", "Could not load results: ${failure.message}")
                if (latestToken === token)
                    value = CleanupResultsLoad(token, error = AppStrings.cleanup.loadError(failure.message))
            }
        }
    }
    val current = load?.takeIf { it.token === token && !blocked }
    LaunchedEffect(current, operationHistory) {
        val bin = current?.binRoot
        if (current?.snapshot != null && bin != null) {
            operationHistory.state.first { !it.busy }
            if (repository.binRoot() == bin && latestToken === current.token) operationHistory.refresh()
        }
    }
    val historySnapshot = history.snapshot.takeIf {
        current?.binRoot != null && history.binRoot == current.binRoot && !history.busy && history.error == null
    }
    val presentation = current?.snapshot?.let { snapshot ->
        remember(snapshot, historySnapshot) { presentCleanupResults(snapshot, historySnapshot) }
    }
    val historyMessage = when {
        current?.binRoot == null -> AppStrings.cleanup.binNotConfigured
        history.busy -> AppStrings.cleanup.historyUpdating
        presentation?.history == null -> AppStrings.cleanup.historyUnavailable
        else -> null
    }
    if (presentation != null) {
        CleanupResultsContent(presentation, historyMessage, onDetailedStatistics,
            onRefresh = { refresh++ }, refreshEnabled = !history.busy, modifier = modifier)
    } else {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(24.dp)) {
            CleanupResultsHeader(onRefresh = { refresh++ }, refreshEnabled = !blocked)
            when {
                blocked -> Text(AppStrings.cleanup.waiting, color = MaterialTheme.colorScheme.onSurfaceVariant)
                current?.error != null -> Text(current.error, color = MaterialTheme.colorScheme.error)
                else -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text(AppStrings.cleanup.loading)
                }
            }
            DetailedStatisticsCard(onDetailedStatistics)
        }
    }
}

@Composable
internal fun CleanupResultsContent(
    data: CleanupResultsPresentation,
    historyMessage: String?,
    onDetailedStatistics: () -> Unit,
    onRefresh: () -> Unit,
    refreshEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        Row(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                state = listState,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item("header") {
                    CleanupSection { CleanupResultsHeader(onRefresh, refreshEnabled) }
                }
                item("overview") {
                    CleanupSection {
                        Text(AppStrings.cleanup.overview, style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (wide) {
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                RecoveryHero(data, historyMessage, Modifier.weight(1.2f))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    InventoryMetrics(data)
                                    CleanupOperationMetrics(data)
                                }
                            }
                        } else {
                            RecoveryHero(data, historyMessage)
                            InventoryMetrics(data)
                            CleanupOperationMetrics(data)
                        }
                    }
                }
                item("intelligence") {
                    CleanupSection { CleanupIntelligence(data, wide) }
                }
                item("duplicates") {
                    CleanupSection { CleanupDuplicateSummary(data) }
                }
                item("details") {
                    CleanupSection {
                        DetailedStatisticsCard(onDetailedStatistics)
                    }
                }
            }
            ResultsScrollbar(listState, Modifier.fillMaxHeight())
        }
    }
}

@Composable
private fun CleanupSection(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.widthIn(max = 1120.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
}

@Composable
private fun CleanupResultsHeader(onRefresh: () -> Unit, refreshEnabled: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(AppStrings.cleanup.title, Modifier.weight(1f),
            style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        OutlinedButton(onClick = onRefresh, enabled = refreshEnabled) { Text(AppStrings.refresh) }
    }
}

@Composable
private fun RecoveryHero(data: CleanupResultsPresentation, historyMessage: String?, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = colors.primaryContainer) {
        Column(
            Modifier.background(Brush.linearGradient(listOf(colors.primaryContainer, colors.tertiaryContainer))).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(AppStrings.cleanup.potentialSavings, style = MaterialTheme.typography.titleLarge,
                color = colors.onPrimaryContainer)
            Text(data.history?.let { formatCleanupBytes(it.logicalBytesInBin) } ?: AppStrings.cleanup.unavailable,
                style = if (data.history == null) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = colors.onPrimaryContainer)
            data.history?.let {
                Surface(shape = CircleShape, color = colors.primary, contentColor = colors.onPrimary) {
                    Text(AppStrings.cleanup.inBin(formatCleanupCount(it.filesInBin)),
                        Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
                }
            }
            if (historyMessage != null)
                Text(historyMessage, style = MaterialTheme.typography.bodySmall, color = colors.onPrimaryContainer)
        }
    }
}

@Composable
private fun InventoryMetrics(data: CleanupResultsPresentation) {
    Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        CleanupMetric(AppStrings.cleanup.totalFiles, formatCleanupCount(data.inventoryFiles),
            AppStrings.cleanup.inventoryNote, Modifier.weight(1f).fillMaxHeight())
        CleanupMetric(AppStrings.cleanup.archiveSize, formatCleanupBytes(data.archiveBytes),
            AppStrings.cleanup.archiveSizeNote, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun CleanupOperationMetrics(data: CleanupResultsPresentation) {
    CleanupMetric(
        AppStrings.cleanup.movesToBin,
        data.history?.let { formatCleanupCount(it.completedMoveEvents) } ?: AppStrings.cleanup.unavailable,
        AppStrings.cleanup.moveEventsNote, Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CleanupMetric(label: String, value: String, note: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CleanupIntelligence(data: CleanupResultsPresentation, wide: Boolean) {
    var selectedSuspected by remember { mutableStateOf<Boolean?>(null) }
    val suspected = selectedSuspected ?: ((data.aiSuspected?.changedFiles ?: 0) > (data.aiRegular?.changedFiles ?: 0) ||
        data.aiRegular == null && data.aiSuspected != null)
    val impact = if (suspected) data.aiSuspected else data.aiRegular
    val colors = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow)) {
        Column(Modifier.padding(if (wide) 28.dp else 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(AppStrings.cleanup.intelligence, style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold)
            Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !suspected, onClick = { selectedSuspected = false },
                    label = { Text(AppStrings.analysis.regularDuplicates) }, modifier = Modifier.weight(1f).fillMaxHeight())
                FilterChip(selected = suspected, onClick = { selectedSuspected = true },
                    label = { Text(AppStrings.analysis.suspectedDuplicates) }, modifier = Modifier.weight(1f).fillMaxHeight())
            }
            if (impact == null) {
                Text(AppStrings.cleanup.aiNotConfigured, color = colors.onSurfaceVariant)
            } else {
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        Column(Modifier.weight(1f)) { CleanupAiComparison(impact) }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CleanupAiChanges(impact)
                        }
                    }
                } else {
                    CleanupAiComparison(impact)
                    CleanupAiChanges(impact)
                }
            }
        }
    }
}

@Composable
private fun CleanupAiComparison(impact: CleanupAiImpact) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            CleanupImpactNumber(impact.changedGroups, AppStrings.cleanup.changedGroups, Modifier.weight(1f))
            CleanupImpactNumber(impact.changedFiles, AppStrings.cleanup.changedFiles, Modifier.weight(1f))
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(AppStrings.cleanup.candidateComparison, style = MaterialTheme.typography.titleSmall)
            val maximum = maxOf(impact.baselineCandidates, impact.currentCandidates)
            CandidateComparisonBar(AppStrings.cleanup.beforeAi, impact.baselineCandidates, maximum, colors.outline)
            CandidateComparisonBar(AppStrings.cleanup.withAi, impact.currentCandidates, maximum, colors.primary)
        }
    }
}

@Composable
private fun CleanupAiChanges(impact: CleanupAiImpact) {
    val colors = MaterialTheme.colorScheme
    CandidateChangeCard(impact.withdrawnCandidates, AppStrings.cleanup.withdrawn,
        AppStrings.cleanup.withdrawnNote, colors.primaryContainer, colors.onPrimaryContainer)
    CandidateChangeCard(impact.newCandidates, AppStrings.cleanup.added,
        AppStrings.cleanup.addedNote, colors.tertiaryContainer, colors.onTertiaryContainer)
}

@Composable
private fun CleanupImpactNumber(value: Long, label: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(formatCleanupCount(value), style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CandidateComparisonBar(label: String, value: Long, maximum: Long, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(formatCleanupCount(value), style = MaterialTheme.typography.labelLarge)
        }
        LinearProgressIndicator(
            progress = { if (maximum == 0L) 0f else (value.toDouble() / maximum.toDouble()).toFloat() },
            modifier = Modifier.fillMaxWidth().height(10.dp),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}

@Composable
private fun CandidateChangeCard(
    count: Long,
    label: String,
    note: String,
    background: Color,
    foreground: Color,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = background, contentColor = foreground) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(formatCleanupCount(count), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            Text(note, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CleanupDuplicateSummary(data: CleanupResultsPresentation) {
    val colors = MaterialTheme.colorScheme
    val typeColors = mapOf(StatisticsFileType.IMAGES to colors.primary, StatisticsFileType.VIDEOS to colors.tertiary,
        StatisticsFileType.DOCUMENTS to colors.secondary, StatisticsFileType.OTHER to colors.outline)
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow)) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(AppStrings.cleanup.duplicates, style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(formatCleanupCount(data.possibleDuplicateFiles), style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.SemiBold)
                    Text(AppStrings.cleanup.possibleDuplicates, style = MaterialTheme.typography.bodyMedium)
                }
                Text(AppStrings.cleanup.archivePercentage(formatInventoryDuplicatePercentage(
                    InventoryFileCounts(data.inventoryFiles, data.possibleDuplicateFiles))),
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = colors.primary)
            }
            if (data.possibleDuplicateFiles == 0L) {
                Text(AppStrings.cleanup.noDuplicates, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            } else {
                Row(Modifier.fillMaxWidth().height(14.dp).clip(CircleShape)
                    .semantics { contentDescription = AppStrings.cleanup.possibleDuplicates }) {
                    data.duplicateTypes.filter { it.count > 0 }.forEach { bucket ->
                        Box(Modifier.weight(bucket.count.toFloat()).fillMaxHeight().background(typeColors.getValue(bucket.type)))
                    }
                }
            }
            data.duplicateTypes.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    row.forEach { bucket ->
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(typeColors.getValue(bucket.type)))
                            Column {
                                Text(AppStrings.analysis.typeLabel(bucket.type), style = MaterialTheme.typography.labelLarge)
                                Text(formatCleanupCount(bucket.count), style = MaterialTheme.typography.bodyMedium,
                                    color = colors.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            Text(AppStrings.cleanup.duplicateNote, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun DetailedStatisticsCard(onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(AppStrings.cleanup.detailedStatistics, style = MaterialTheme.typography.titleMedium)
                Text(AppStrings.cleanup.detailedSubtitle, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(AppStrings.cleanup.open, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}
