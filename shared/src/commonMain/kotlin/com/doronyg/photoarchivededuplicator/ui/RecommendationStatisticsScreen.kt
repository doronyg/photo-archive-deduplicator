package com.doronyg.photoarchivededuplicator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.doronyg.photoarchivededuplicator.UiPreferences
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassification
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationController
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationJobState
import com.doronyg.photoarchivededuplicator.exif.ExifQueueController
import com.doronyg.photoarchivededuplicator.operations.OperationHistoryController
import com.doronyg.photoarchivededuplicator.recommendation.FileRecommendationRole
import com.doronyg.photoarchivededuplicator.recommendation.FileStatistics
import com.doronyg.photoarchivededuplicator.recommendation.InventoryFileCounts
import com.doronyg.photoarchivededuplicator.recommendation.RecommendationAction
import com.doronyg.photoarchivededuplicator.recommendation.RecommendationStatistics
import com.doronyg.photoarchivededuplicator.recommendation.RecommendationStatisticsBucket
import com.doronyg.photoarchivededuplicator.recommendation.RecommendationStatisticsReport
import com.doronyg.photoarchivededuplicator.recommendation.RecommendationStatisticsService
import com.doronyg.photoarchivededuplicator.recommendation.StatisticsFileType
import com.doronyg.photoarchivededuplicator.retention.RetentionScoreService
import com.doronyg.photoarchivededuplicator.scan.DirectoryRepository
import com.doronyg.photoarchivededuplicator.strings.AppStrings
import com.doronyg.photoarchivededuplicator.strings.OperationHistoryStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Path
import kotlin.math.roundToInt

internal fun formatStatisticsMegabytes(sizeBytes: Long): String {
    require(sizeBytes >= 0) { AppStrings.analysis.negativeSize }
    val fraction = (sizeBytes % 1_000_000 + 5_000) / 10_000
    val whole = sizeBytes / 1_000_000 + fraction / 100
    return AppStrings.analysis.megabytes(whole, fraction % 100)
}

internal fun formatInventoryDuplicatePercentage(counts: InventoryFileCounts): String {
    val hundredths = if (counts.totalFiles == 0L) 0 else
        (counts.possibleDuplicateFiles.toDouble() / counts.totalFiles.toDouble() * 10_000).roundToInt()
    return AppStrings.analysis.percentage(hundredths / 100, hundredths % 100)
}

internal fun formatRecommendationStatistics(report: RecommendationStatisticsReport): String = buildString {
    val strings = AppStrings.analysis
    fun inventoryRow(label: String, counts: InventoryFileCounts) =
        strings.inventoryRow(label, counts.totalFiles, counts.possibleDuplicateFiles, formatInventoryDuplicatePercentage(counts))
    appendLine(strings.inventoryTitle)
    appendLine(inventoryRow(AppStrings.duplicates.all, report.inventory.all))
    for (type in StatisticsFileType.entries) {
        appendLine(inventoryRow(strings.typeLabel(type), report.inventory.byFileType.getValue(type)))
    }
    appendLine(strings.inventoryDuplicateDefinition)
    appendLine()
    appendLine(strings.statisticsUnits)
    appendLine(strings.cohortOverlap)
    appendLine(strings.suspectedScope)
    appendLine(strings.reviewDefinition)
    appendLine(if (report.classificationContext != null) strings.cachedLabels else strings.aiUnconfigured)
    if (report.regular.ai != null || report.suspected.ai != null) {
        appendLine(strings.unknownDefinition)
        appendLine(strings.directoryCountDefinition)
        appendLine(strings.impactDefinition)
        appendLine(strings.decisionDefinition)
    }
    fun totals(value: FileStatistics) = strings.fileTotals(value.fileCount, formatStatisticsMegabytes(value.sizeBytes))
    fun actionLabel(action: RecommendationAction) = strings.actionLabel(action)
    fun roleLabel(role: FileRecommendationRole) = strings.roleLabel(role)
    fun typeLabel(type: StatisticsFileType) = strings.typeLabel(type)
    fun bucket(label: String, value: RecommendationStatisticsBucket) {
        appendLine(strings.bucket(label, totals(value.totals)))
        for (type in StatisticsFileType.entries) {
            appendLine(strings.typeBucket(typeLabel(type), totals(value.byFileType.getValue(type))))
        }
    }
    fun section(label: String, statistics: RecommendationStatistics) {
        appendLine()
        appendLine(label)
        appendLine(strings.groupTotals(statistics.groupCount, totals(statistics.totals)))
        statistics.ai?.let { ai ->
            appendLine()
            appendLine(strings.coverageTitle)
            appendLine(strings.classified(ai.classifiedDirectories, ai.directoryCount, totals(ai.classifiedFiles)))
            for (classification in DirectoryClassification.entries) {
                val value = ai.byClassification.getValue(classification)
                appendLine(strings.classificationBucket(classification.name, value.directoryCount, totals(value.files)))
            }
            appendLine(strings.classificationBucket(strings.missingClassification, ai.missing.directoryCount, totals(ai.missing.files)))
            appendLine(strings.groupCoverage(ai.fullyClassifiedGroups, ai.partiallyClassifiedGroups, ai.unclassifiedGroups))
            appendLine()
            appendLine(strings.impactTitle)
            appendLine(strings.changedGroups(ai.changedGroups, statistics.groupCount))
            appendLine(strings.changedActions(ai.changedActionGroups))
            appendLine(strings.changedFiles(totals(ai.changedFiles)))
            appendLine(strings.changedReplacements(totals(ai.changedReplacements)))
            appendLine(strings.baselineCandidates(totals(ai.baselineRedundantCandidates)))
            appendLine(strings.currentCandidates(totals(ai.currentRedundantCandidates)))
            appendLine(strings.addedCandidates(totals(ai.addedRedundantCandidates)))
            appendLine(strings.withdrawnCandidates(totals(ai.withdrawnRedundantCandidates)))
            appendLine(strings.actionTransitionsTitle)
            if (ai.actionTransitions.isEmpty()) appendLine(strings.noTransitions)
            for (before in RecommendationAction.entries) for (after in RecommendationAction.entries) {
                ai.actionTransitions[before to after]?.let {
                    appendLine(strings.actionTransition(actionLabel(before), actionLabel(after), it))
                }
            }
            appendLine(strings.roleTransitionsTitle)
            if (ai.roleTransitions.isEmpty()) appendLine(strings.noTransitions)
            for (before in FileRecommendationRole.entries) for (after in FileRecommendationRole.entries) {
                ai.roleTransitions[before to after]?.let {
                    appendLine(strings.roleTransition(roleLabel(before), roleLabel(after), it))
                }
            }
        }
        appendLine()
        appendLine(strings.byGroup)
        for (action in RecommendationAction.entries) {
            bucket(strings.actionBucket(actionLabel(action), statistics.groupCounts.getValue(action)), statistics.byAction.getValue(action))
        }
        appendLine()
        appendLine(strings.byRole)
        for (role in FileRecommendationRole.entries) {
            bucket(roleLabel(role), statistics.byRole.getValue(role))
        }
        appendLine()
        appendLine(strings.byType)
        for (type in StatisticsFileType.entries) {
            appendLine(strings.bucket(typeLabel(type), totals(statistics.byFileType.getValue(type))))
        }
    }
    section(strings.regularDuplicates, report.regular)
    section(strings.suspectedDuplicates, report.suspected)
}

@Composable
internal fun RecommendationStatisticsScreen(
    repository: DirectoryRepository,
    retention: RetentionScoreService,
    queue: ExifQueueController,
    scanning: Boolean,
    modifier: Modifier = Modifier,
    directoryClassifications: DirectoryClassificationController? = null,
    preferences: UiPreferences? = null,
    operationHistory: OperationHistoryController? = null,
) {
    val historyState = operationHistory?.state?.collectAsState()?.value
    var historyBin by remember(repository) { mutableStateOf<Path?>(null) }
    val settings = preferences?.state?.collectAsState()?.value?.ollama
    val classificationContext = remember(settings) { settings?.let { runCatching { it.context() }.getOrNull() } }
    val classificationState = directoryClassifications?.state?.collectAsState()?.value ?: DirectoryClassificationJobState()
    val service = remember(repository, retention, classificationContext) {
        RecommendationStatisticsService(repository, retention, classificationContext = classificationContext)
    }
    val queueState by queue.state.collectAsState()
    val queueKey = exifQueueRefreshKey(queueState)
    val latestScanning by rememberUpdatedState(scanning)
    var refresh by remember { mutableIntStateOf(0) }
    var generation by remember { mutableIntStateOf(0) }
    var text by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val blocked = scanning || queueState.isRunning
    LaunchedEffect(operationHistory, refresh) {
        historyBin = repository.binRoot()
        if (historyBin != null && operationHistory?.state?.value?.busy == false) operationHistory.refresh()
    }

    LaunchedEffect(service, queueKey, blocked, refresh, classificationState.revision) {
        val request = ++generation
        text = null
        error = null
        loading = !blocked
        if (!blocked) {
            try {
                val formatted = withContext(Dispatchers.Default) { formatRecommendationStatistics(service.load()) }
                if (request == generation && !latestScanning && !queue.state.value.isRunning &&
                    queueKey == exifQueueRefreshKey(queue.state.value)) text = formatted
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (request == generation) error = AppStrings.analysis.statisticsError(e.message)
            } finally {
                if (request == generation) loading = false
            }
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(AppStrings.analysis.statisticsTitle, style = MaterialTheme.typography.titleLarge)
        Text(AppStrings.analysis.statisticsScope,
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled = !blocked && !loading, onClick = { refresh++ }) { Text(AppStrings.refresh) }
        when {
            blocked -> Text(AppStrings.analysis.statisticsBlocked)
            loading -> Text(AppStrings.loading)
            error != null -> Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
            text != null -> SelectionContainer(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (historyBin != null && historyState != null && historyState.binRoot == historyBin) {
                        historyState.snapshot?.let { saved ->
                            Text(operationHistoryStatisticsTitle(saved), style = MaterialTheme.typography.titleMedium)
                            if (operationHistoryTotalsPartial(saved)) Text(OperationHistoryStrings.incomplete)
                            operationHistoryStatisticsFields(saved).forEach { (label, value) ->
                                Text(AppStrings.field(label, value))
                            }
                            Text(OperationHistoryStrings.eventCountNote, style = MaterialTheme.typography.bodySmall)
                            Text(OperationHistoryStrings.physicalSpace, style = MaterialTheme.typography.bodySmall)
                        }
                        historyState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                    Text(text.orEmpty(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
