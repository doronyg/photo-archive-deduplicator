package com.doronyg.photoarchivededuplicator.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.doronyg.photoarchivededuplicator.PathActions
import com.doronyg.photoarchivededuplicator.exif.ExifQueueController
import com.doronyg.photoarchivededuplicator.exif.ExifQueueState
import com.doronyg.photoarchivededuplicator.exif.ExifQueuePhase
import com.doronyg.photoarchivededuplicator.exif.ExifRecord
import com.doronyg.photoarchivededuplicator.scan.DirectoryRepository
import com.doronyg.photoarchivededuplicator.scan.SUSPECTED_IMAGE_MIN_SIZE_BYTES
import com.doronyg.photoarchivededuplicator.scan.SuspectedDuplicateGroup
import com.doronyg.photoarchivededuplicator.retention.RetentionScoreService
import com.doronyg.photoarchivededuplicator.retention.RetentionPresentation
import com.doronyg.photoarchivededuplicator.retention.sortSuspectedFilesByRetention
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okio.Path

internal fun shouldAssessSuspectedScores(
    state: ExifQueueState,
    scanning: Boolean,
    terminalRefresh: Boolean,
    force: Boolean,
): Boolean = !scanning && !state.isRunning && !terminalRefresh &&
    (force || (state.phase != ExifQueuePhase.CANCELLED && state.phase != ExifQueuePhase.FAILED))

@Composable
internal fun SuspectedDuplicatesScreen(
    repository: DirectoryRepository,
    retentionScores: RetentionScoreService,
    queue: ExifQueueController,
    pathActions: PathActions,
    scanning: Boolean,
    modifier: Modifier = Modifier,
) {
    val queueState by queue.state.collectAsState()
    var observedRevision by remember(queue) { mutableStateOf(queueState.revision) }
    var loadedRevision by remember(queue) { mutableStateOf<Long?>(null) }
    var groups by remember { mutableStateOf<List<SuspectedDuplicateGroup>>(emptyList()) }
    var scores by remember { mutableStateOf<Map<Path, RetentionPresentation>>(emptyMap()) }
    var exif by remember { mutableStateOf<Map<Path, ExifRecord>>(emptyMap()) }
    var forceNextRefresh by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var generation by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var cancelling by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val recommendationGroups = remember(groups) { groups.map { it.files } }
    val recommendations = rememberDuplicateRecommendations(recommendationGroups, exif, scores,
        evidenceReady = !loading && !scanning && !queueState.isRunning && loadedRevision == queueState.revision)

    LaunchedEffect(repository, retentionScores, queue, queueState.revision, refresh, scanning) {
        val request = ++generation
        val revision = queueState.revision
        val terminalRefresh = revision != observedRevision
        observedRevision = revision
        val force = forceNextRefresh
        forceNextRefresh = false
        loading = true
        loadedRevision = null
        groups = emptyList()
        scores = emptyMap()
        exif = emptyMap()
        error = null
        try {
            val matches = repository.suspectedDuplicates()
            val assessment = if (shouldAssessSuspectedScores(queue.state.value, scanning, terminalRefresh, force)) {
                retentionScores.assessSuspected(matches, force)
            } else retentionScores.storedSuspected(matches)
            val storedExif = repository.fileExif(matches.flatMap { it.files }.map { it.path }, currentOnly = true)
                .associateBy { it.input.path }
            val ordered = sortSuspectedFilesByRetention(matches, assessment.files)
            currentCoroutineContext().ensureActive()
            if (request == generation && revision == queue.state.value.revision) {
                groups = ordered
                scores = assessment.files
                exif = storedExif
                loadedRevision = revision
                if (assessment.invalidatedFiles > 0) {
                    error = "Inventory or EXIF changed since these matches were loaded. Refresh to reload the affected groups."
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (request == generation) error = "Could not load suspected duplicates or retention scores: ${e.message}"
        } finally {
            if (request == generation) loading = false
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Suspected duplicates", style = MaterialTheme.typography.titleLarge)
        Text(
            "Images larger than $SUSPECTED_IMAGE_MIN_SIZE_BYTES bytes across the stored inventory, excluding excluded folders. " +
                "Only same-size buckets with different filenames are inspected. Unchanged EXIF attempts are reused.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(enabled = !scanning && !queueState.isRunning, onClick = {
                error = null
                if (!queue.startSuspected()) error = "Analysis could not start: a scan or EXIF queue is already running."
            }) { Text("Analyze suspected duplicates") }
            OutlinedButton(enabled = !loading && !queueState.isRunning, onClick = { refresh++ }) {
                Text("Refresh stored matches")
            }
            OutlinedButton(enabled = !loading && !scanning && !queueState.isRunning && groups.isNotEmpty(), onClick = {
                forceNextRefresh = true
                refresh++
            }) { Text("Recompute scores") }
            if (queueState.isRunning) {
                TextButton(enabled = !cancelling, onClick = {
                    cancelling = true
                    scope.launch {
                        try {
                            queue.cancel()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = "Could not cancel EXIF analysis: ${e.message}"
                        } finally {
                            cancelling = false
                        }
                    }
                }) { Text(if (cancelling) "Cancelling..." else "Cancel EXIF queue") }
            }
        }
        presentExifQueue(queueState)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text(
            "Matches require successful, current EXIF with the same original capture date/time to the second " +
                "and compatible original offsets, plus identical byte sizes and at least two different filenames. " +
                "These are suspected matches, not content-verified duplicates. No files are changed.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Highest retention scores appear first across the whole group, using stored metadata only. " +
                "Tap a score for its factors. Ties and close scores remain ambiguous.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (queueState.isRunning || scanning) {
            Text("Stored matches may be incomplete while scanning or EXIF analysis is in progress.",
                style = MaterialTheme.typography.bodySmall)
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        SelectionContainer(Modifier.weight(1f)) {
            Row(Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Text(if (loading) "Loading stored matches and scores..." else "${groups.size} suspected duplicate groups.")
                    }
                    if (!loading && groups.isEmpty()) {
                        item { Text("No matching stored EXIF timestamps. Analyze inspects eligible images and reuses cached attempts; missing or failed EXIF is not a match.") }
                    }
                    groups.forEach { group ->
                        item {
                            HorizontalDivider()
                            Text(
                                "Captured ${group.capturedAt} (${group.offsetOriginal ?: "timezone unknown"})",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text("${group.sizeBytes} bytes (${formatBytes(group.sizeBytes)}) · ${group.files.size} files")
                            DuplicateRecommendationSummary(group.files.firstOrNull()?.let { recommendations?.get(it.path) })
                        }
                        items(group.files, key = { it.path.toString() }) { file ->
                            Column {
                                FilePathLinks(file, pathActions)
                                Text(
                                    "Created ${formatEpochMillis(file.createdAt)} · modified ${formatEpochMillis(file.modifiedAt)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                RetentionScoreLine(file.path, scores[file.path])
                                FileRecommendationLine(file.path, recommendations?.get(file.path)?.files?.get(file.path))
                                ExifMetadataLine(file.path, exif[file.path])
                            }
                        }
                    }
                }
                DisableSelection { ResultsScrollbar(listState, Modifier.fillMaxHeight()) }
            }
        }
    }
}
