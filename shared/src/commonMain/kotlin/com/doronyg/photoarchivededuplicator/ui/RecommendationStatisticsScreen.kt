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
import com.doronyg.photoarchivededuplicator.exif.ExifQueueController
import com.doronyg.photoarchivededuplicator.UiPreferences
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationController
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationJobState
import com.doronyg.photoarchivededuplicator.recommendation.FileRecommendationRole
import com.doronyg.photoarchivededuplicator.recommendation.FileStatistics
import com.doronyg.photoarchivededuplicator.recommendation.RecommendationAction
import com.doronyg.photoarchivededuplicator.recommendation.RecommendationStatistics
import com.doronyg.photoarchivededuplicator.recommendation.RecommendationStatisticsBucket
import com.doronyg.photoarchivededuplicator.recommendation.RecommendationStatisticsReport
import com.doronyg.photoarchivededuplicator.recommendation.RecommendationStatisticsService
import com.doronyg.photoarchivededuplicator.recommendation.StatisticsFileType
import com.doronyg.photoarchivededuplicator.retention.RetentionScoreService
import com.doronyg.photoarchivededuplicator.scan.DirectoryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun formatStatisticsMegabytes(sizeBytes: Long): String {
    require(sizeBytes >= 0) { "Statistics byte size must be nonnegative" }
    val fraction = (sizeBytes % 1_000_000 + 5_000) / 10_000
    val whole = sizeBytes / 1_000_000 + fraction / 100
    return "$whole.${(fraction % 100).toString().padStart(2, '0')} MB"
}

internal fun formatRecommendationStatistics(report: RecommendationStatisticsReport): String = buildString {
    appendLine("MB = 1,000,000 bytes. Sizes include every listed file, not estimated reclaimable space.")
    appendLine("Regular and suspected sections can overlap; do not add their totals together.")
    appendLine("Suspected totals include stored EXIF matches, not the unanalyzed same-size inspection queue.")
    appendLine("Missing/stale scores or capture evidence count as Review. No files are content-verified or changed.")
    appendLine("Statistics do not extract EXIF or recompute retention scores.")
    fun totals(value: FileStatistics) = "${value.fileCount} files | ${formatStatisticsMegabytes(value.sizeBytes)}"
    fun typeLabel(type: StatisticsFileType) = when (type) {
        StatisticsFileType.IMAGES -> "Images"
        StatisticsFileType.VIDEOS -> "Videos"
        StatisticsFileType.DOCUMENTS -> "Documents"
        StatisticsFileType.OTHER -> "Other"
    }
    fun bucket(label: String, value: RecommendationStatisticsBucket) {
        appendLine("$label: ${totals(value.totals)}")
        for (type in StatisticsFileType.entries) {
            appendLine("  ${typeLabel(type)}: ${totals(value.byFileType.getValue(type))}")
        }
    }
    fun section(label: String, statistics: RecommendationStatistics) {
        appendLine()
        appendLine(label)
        appendLine("${statistics.groupCount} groups | ${totals(statistics.totals)}")
        appendLine()
        appendLine("By group recommendation (all files in each group)")
        for (action in RecommendationAction.entries) {
            val name = when (action) {
                RecommendationAction.KEEP_ONE -> "Keep one"
                RecommendationAction.KEEP_MULTIPLE -> "Keep multiple"
                RecommendationAction.REVIEW -> "Review"
                RecommendationAction.PROTECTED -> "Protected"
            }
            bucket(name, statistics.byAction.getValue(action))
        }
        appendLine()
        appendLine("By file role")
        for (role in FileRecommendationRole.entries) {
            val name = when (role) {
                FileRecommendationRole.PREFERRED -> "Preferred"
                FileRecommendationRole.REDUNDANT_CANDIDATE -> "Likely redundant"
                FileRecommendationRole.PRESERVE -> "Preserve"
                FileRecommendationRole.REVIEW -> "Review"
            }
            bucket(name, statistics.byRole.getValue(role))
        }
        appendLine()
        appendLine("By file type (section total)")
        for (type in StatisticsFileType.entries) {
            appendLine("${typeLabel(type)}: ${totals(statistics.byFileType.getValue(type))}")
        }
    }
    section("Regular duplicates", report.regular)
    section("Suspected duplicates", report.suspected)
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
) {
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
                if (request == generation) error = "Could not load recommendation statistics: ${e.message}"
            } finally {
                if (request == generation) loading = false
            }
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Recommendation statistics", style = MaterialTheme.typography.titleLarge)
        Text("All stored roots, excluding excluded folders. Independent of duplicate-tab search filters.",
            style = MaterialTheme.typography.bodySmall)
        Text("Uses cached AI classifications for the current Ollama settings only; never contacts the server.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled = !blocked && !loading, onClick = { refresh++ }) { Text("Refresh statistics") }
        when {
            blocked -> Text("Statistics wait until scanning and EXIF processing stop, to avoid showing partial analysis.")
            loading -> Text("Calculating statistics from stored metadata...")
            error != null -> Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
            text != null -> SelectionContainer(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(text.orEmpty(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
