package com.doronyg.photoarchivededuplicator.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationContext
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationController
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationJobState
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationPhase
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationRecord
import com.doronyg.photoarchivededuplicator.classification.OllamaSettings
import com.doronyg.photoarchivededuplicator.classification.directoryClassificationPaths
import com.doronyg.photoarchivededuplicator.scan.DirectoryRepository
import com.doronyg.photoarchivededuplicator.scan.DuplicateCandidateGroup
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import com.doronyg.photoarchivededuplicator.scan.inventoryPath
import com.doronyg.photoarchivededuplicator.scan.isInTree
import com.doronyg.photoarchivededuplicator.strings.AppStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import okio.Path

internal data class DirectoryClassificationCounts(val total: Int, val missing: Int)

internal fun completeDuplicateGroupsUnderRoot(
    groups: List<DuplicateCandidateGroup>,
    root: Path,
): List<DuplicateCandidateGroup> {
    val normalized = root.inventoryPath()
    return groups.filter { group -> group.files.any { it.path.isInTree(normalized) } }
}

internal fun directoryClassificationCounts(
    groups: List<List<FileRecord>>,
    records: Map<Path, DirectoryClassificationRecord>,
): DirectoryClassificationCounts {
    val paths = directoryClassificationPaths(groups)
    return DirectoryClassificationCounts(paths.size, paths.count { it !in records })
}

internal fun directoryClassificationProgressText(state: DirectoryClassificationJobState): String =
    AppStrings.analysis.classificationProgress(state)

internal fun directoryClassificationRecordText(record: DirectoryClassificationRecord): String =
    AppStrings.analysis.classificationLabel(record.classification)

internal data class CachedDirectoryClassifications(
    val records: Map<Path, DirectoryClassificationRecord> = emptyMap(),
    val ready: Boolean = false,
    val error: String? = null,
)

private data class ClassificationCacheSnapshot(val token: Any, val result: CachedDirectoryClassifications)

internal fun pendingCachedDirectoryClassifications(
    previous: CachedDirectoryClassifications?,
    paths: List<Path>,
    context: DirectoryClassificationContext?,
): CachedDirectoryClassifications {
    val requested = paths.toSet()
    return CachedDirectoryClassifications(records = previous?.records.orEmpty().filter { (path, record) ->
        path in requested && record.context == context
    })
}

internal suspend fun loadCachedDirectoryClassifications(
    paths: List<Path>,
    context: DirectoryClassificationContext?,
    load: suspend (List<Path>, DirectoryClassificationContext) -> List<DirectoryClassificationRecord>,
): CachedDirectoryClassifications {
    if (context == null) return CachedDirectoryClassifications(ready = true)
    return try {
        val requested = paths.map { it.inventoryPath() }.toSet()
        val records = load(requested.toList(), context)
            .filter { it.context == context && it.directoryPath.inventoryPath() in requested }
            .associateBy { it.directoryPath.inventoryPath() }
        CachedDirectoryClassifications(records, ready = true)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        CachedDirectoryClassifications(error = AppStrings.analysis.cacheError(e.message))
    }
}

@Composable
internal fun rememberCachedDirectoryClassifications(
    repository: DirectoryRepository,
    groups: List<List<FileRecord>>,
    context: DirectoryClassificationContext?,
    revision: Long,
    refresh: Int = 0,
): CachedDirectoryClassifications {
    val paths = remember(groups) { directoryClassificationPaths(groups) }
    val token = remember(repository, paths, context, revision, refresh) { Any() }
    val snapshot by produceState<ClassificationCacheSnapshot?>(null, token) {
        val pending = pendingCachedDirectoryClassifications(value?.result, paths, context)
        val result = loadCachedDirectoryClassifications(paths, context, repository::directoryClassifications)
        value = ClassificationCacheSnapshot(token,
            if (result.ready) result else result.copy(records = pending.records))
    }
    if (context == null) return CachedDirectoryClassifications(ready = true)
    return snapshot?.takeIf { it.token === token }?.result
        ?: pendingCachedDirectoryClassifications(snapshot?.result, paths, context)
}

@Composable
internal fun DirectoryClassificationControls(
    controller: DirectoryClassificationController,
    settings: OllamaSettings,
    groups: List<List<FileRecord>>?,
    cache: CachedDirectoryClassifications,
    onReloadCache: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val context = remember(settings) { runCatching { settings.context() }.getOrNull() }
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var cancelling by remember { mutableStateOf(false) }
    var controlsExpanded by remember { mutableStateOf(false) }
    val counts = remember(groups, cache.records) { directoryClassificationCounts(groups.orEmpty(), cache.records) }
    val enabled = context != null && groups != null && counts.total > 0 && cache.ready && !state.isRunning
    val retryCount = remember(groups, state.failures) {
        val paths = directoryClassificationPaths(groups.orEmpty()).toSet()
        state.failures.count { it.directory.inventoryPath() in paths }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { controlsExpanded = !controlsExpanded }) {
                Text(if (controlsExpanded) AppStrings.analysis.hideClassification else AppStrings.analysis.directoryClassification)
            }
            Text(AppStrings.analysis.classificationCounts(counts.total, counts.missing, cache.ready),
                style = MaterialTheme.typography.bodySmall)
        }
        if (state.phase != DirectoryClassificationPhase.IDLE) {
            Text(directoryClassificationProgressText(state), style = MaterialTheme.typography.bodySmall)
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (controlsExpanded) Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(AppStrings.analysis.classificationScope,
            style = MaterialTheme.typography.bodySmall)
        if (context == null) Text(AppStrings.analysis.configureClassification)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = enabled && counts.missing > 0, onClick = {
                error = null
                if (!controller.start(groups.orEmpty().map { it.toList() }, context!!)) error = AppStrings.analysis.cannotStart
            }) { Text(AppStrings.analysis.classifyMissing) }
            OutlinedButton(enabled = enabled, onClick = {
                error = null
                if (!controller.start(groups.orEmpty().map { it.toList() }, context!!, forceRefresh = true)) error = AppStrings.analysis.cannotStart
            }) { Text(AppStrings.analysis.refreshResults) }
            OutlinedButton(enabled = enabled && retryCount > 0 && state.context == context, onClick = {
                error = null
                if (!controller.retryFailed(groups.orEmpty().map { it.toList() }, context!!)) error = AppStrings.analysis.cannotRetry
            }) { Text(AppStrings.analysis.retryFailed(retryCount)) }
            if (state.isRunning) OutlinedButton(enabled = !cancelling, onClick = {
                cancelling = true
                scope.launch {
                    try {
                        controller.cancel()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = AppStrings.analysis.cancelError(e.message)
                    } finally {
                        cancelling = false
                    }
                }
            }) { Text(if (cancelling) AppStrings.analysis.cancelling else AppStrings.cancel) }
        }
        state.failures.forEach { Text(AppStrings.analysis.directoryFailure(quoteResultText(it.directory.toString()), quoteResultText(it.message)),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        cache.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = onReloadCache) { Text(AppStrings.analysis.retryCache) }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
