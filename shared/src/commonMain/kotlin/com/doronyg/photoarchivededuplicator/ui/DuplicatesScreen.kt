package com.doronyg.photoarchivededuplicator.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.doronyg.photoarchivededuplicator.scan.DirectoryRepository
import com.doronyg.photoarchivededuplicator.PathActions
import com.doronyg.photoarchivededuplicator.UiPreferences
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationController
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationJobState
import com.doronyg.photoarchivededuplicator.scan.inventoryPath
import com.doronyg.photoarchivededuplicator.exif.ExifExtractionService
import com.doronyg.photoarchivededuplicator.exif.ExifQueueController
import com.doronyg.photoarchivededuplicator.exif.ExifQueuePhase
import com.doronyg.photoarchivededuplicator.exif.ExifRecord
import com.doronyg.photoarchivededuplicator.recommendation.DuplicateGroupRecommendation
import com.doronyg.photoarchivededuplicator.retention.RetentionAssessmentResult
import com.doronyg.photoarchivededuplicator.retention.RetentionScoreService
import com.doronyg.photoarchivededuplicator.retention.RetentionPresentation
import com.doronyg.photoarchivededuplicator.retention.sortDuplicateFilesByRetention
import com.doronyg.photoarchivededuplicator.scan.DuplicateCandidateGroup
import com.doronyg.photoarchivededuplicator.scan.DuplicateCandidateFilter
import com.doronyg.photoarchivededuplicator.scan.DuplicateFileType
import com.doronyg.photoarchivededuplicator.scan.DuplicateSortRule
import com.doronyg.photoarchivededuplicator.scan.DuplicateSortCriterion
import com.doronyg.photoarchivededuplicator.scan.sortDuplicateCandidates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import okio.Path
import com.doronyg.photoarchivededuplicator.strings.AppStrings
import okio.Path.Companion.toPath

private data class SortedCandidates(
    val source: List<DuplicateCandidateGroup>,
    val rules: List<DuplicateSortRule>,
    val retention: Map<Path, RetentionPresentation>,
    val recommendations: Map<Path, DuplicateGroupRecommendation>?,
    val groups: List<DuplicateCandidateGroup>,
)

@Composable
fun DuplicatesScreen(repository: DirectoryRepository, target: ScanTarget, pathActions: PathActions, preferences: UiPreferences, retentionScores: RetentionScoreService, exifService: ExifExtractionService, queue: ExifQueueController, scanRunning: Boolean, modifier: Modifier = Modifier, directoryClassifications: DirectoryClassificationController? = null) {
    val choices by preferences.state.collectAsState()
    val queueState by queue.state.collectAsState()
    val queueRefreshKey = exifQueueRefreshKey(queueState)
    val rootText = choices.duplicateRoot
    val fileType = choices.duplicateFileType
    val filenamePattern = choices.duplicateFilenamePattern
    var roots by remember { mutableStateOf(emptyList<Path>()) }
    var groups by remember { mutableStateOf<List<DuplicateCandidateGroup>?>(null) }
    var searchedRoot by remember { mutableStateOf("") }
    var searchedFilter by remember { mutableStateOf(DuplicateCandidateFilter()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var scoring by remember { mutableStateOf(false) }
    var assessmentGeneration by remember { mutableStateOf(0L) }
    var retention by remember { mutableStateOf<RetentionAssessmentResult?>(null) }
    var copying by remember { mutableStateOf(false) }
    var exif by remember { mutableStateOf<Map<Path, ExifRecord>>(emptyMap()) }
    var exifLoading by remember { mutableStateOf(false) }
    var cancellingQueue by remember { mutableStateOf(false) }
    var observedQueueRefreshKey by remember(queue) { mutableStateOf(queueRefreshKey) }
    var exifGeneration by remember { mutableStateOf(0L) }
    var loadedExifToken by remember { mutableStateOf<ExifSnapshotToken?>(null) }
    var loadedExifKey by remember { mutableStateOf<ExifQueueRefreshKey?>(null) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()
    val reportRetention = retention?.files.orEmpty()
    val reportRoot = searchedRoot
    val reportFilter = searchedFilter
    // A new source/filter gets a fresh token; changing only sort order deliberately does not.
    val exifToken = remember(groups, rootText, fileType, filenamePattern, repository, exifService, queue, exifGeneration) { ExifSnapshotToken() }
    val recommendationGroups = remember(groups) { groups.orEmpty().map { it.files } }
    val classificationState = directoryClassifications?.state?.collectAsState()?.value ?: DirectoryClassificationJobState()
    val classificationContext = remember(choices.ollama) { runCatching { choices.ollama.context() }.getOrNull() }
    var classificationCacheRefresh by remember { mutableStateOf(0) }
    val classificationCache = rememberCachedDirectoryClassifications(repository, recommendationGroups,
        classificationContext, classificationState.revision, classificationCacheRefresh)
    val recommendations = rememberDuplicateRecommendations(recommendationGroups, exif, reportRetention,
        evidenceReady = classificationCache.ready && !scanRunning && !queueState.isRunning && !scoring && !exifLoading &&
            loadedExifToken?.isCurrent(exifToken) == true && loadedExifKey == queueRefreshKey,
        directoryClassifications = classificationCache.records)
    val sortRecommendations = recommendations.takeIf {
        choices.duplicateSort.any { rule -> rule.enabled && rule.criterion == DuplicateSortCriterion.RECOMMENDATION }
    }
    val sorted by produceState<SortedCandidates?>(null, groups, choices.duplicateSort, reportRetention, sortRecommendations) {
        value = null
        val source = groups
        val rules = choices.duplicateSort
        val scores = reportRetention
        val advice = sortRecommendations
        if (source != null) {
            val orderedGroups = sortDuplicateCandidates(source, rules, recommendations = advice.orEmpty())
            value = SortedCandidates(source, rules, scores, advice, sortDuplicateFilesByRetention(orderedGroups, scores))
        }
    }
    val displayedSnapshot = sorted?.takeIf {
        it.source === groups && it.rules == choices.duplicateSort && it.retention === reportRetention &&
            it.recommendations === sortRecommendations
    }
    var copyStatus by remember(groups, choices.duplicateSort, retention, exif, recommendations) { mutableStateOf<String?>(null) }
    val latestExifToken by rememberUpdatedState(exifToken)
    fun isCurrent(token: ExifSnapshotToken) = token.isCurrent(latestExifToken)
    fun invalidateExif() {
        assessmentGeneration++
        scoring = false
        retention = null
        exifToken.invalidate()
        exifGeneration++
        exif = emptyMap()
    }

    DisposableEffect(repository, retentionScores) {
        onDispose { assessmentGeneration++ }
    }

    DisposableEffect(exifToken) {
        onDispose {
            exifToken.invalidate()
        }
    }
    LaunchedEffect(exifToken, queueRefreshKey) {
        val refreshKey = queueRefreshKey
        val queueChanged = observedQueueRefreshKey.revision != refreshKey.revision ||
            (refreshKey.terminalPhase != null && observedQueueRefreshKey != refreshKey)
        observedQueueRefreshKey = refreshKey
        if (queueChanged) {
            assessmentGeneration++
            scoring = false
        }
        val generation = assessmentGeneration
        fun current() = isCurrent(exifToken) && refreshKey == exifQueueRefreshKey(queue.state.value)
        val loadScores = queueChanged || (!scoring && queueState.phase != ExifQueuePhase.IDLE)
        exif = emptyMap()
        exifLoading = true
        val source = groups
        try {
            if (source != null) {
                val stored = exifService.stored(source)
                val scores = if (loadScores) retentionScores.stored(source) else null
                if (current()) {
                    exif = stored
                    loadedExifToken = exifToken
                    loadedExifKey = refreshKey
                    if (scores != null && generation == assessmentGeneration) retention = scores
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (current()) error = AppStrings.duplicates.storedError(e.message)
        } finally {
            if (current()) exifLoading = false
        }
    }

    suspend fun assessResults(candidates: List<DuplicateCandidateGroup>, force: Boolean = false) {
        val generation = ++assessmentGeneration
        fun current() = generation == assessmentGeneration && groups === candidates
        scoring = true
        try {
            val result = if (queue.state.value.isRunning) retentionScores.stored(candidates)
                else retentionScores.assess(candidates, force)
            if (current()) {
                retention = result
                if (result.invalidatedFiles > 0) {
                    error = AppStrings.duplicates.scoringChanged
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (current()) error = AppStrings.duplicates.scoringError(e.message)
        } finally {
            if (current()) scoring = false
        }
    }

    LaunchedEffect(repository) {
        try {
            roots = repository.childOverviews(null).map { it.directory.path }
            val defaultRoot = (target as? ScanTarget.Fixed)?.root ?: roots.firstOrNull()
            if (defaultRoot != null) {
                preferences.update {
                    if (it.duplicateRoot.isBlank()) it.copy(duplicateRoot = defaultRoot.toString()) else it
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = AppStrings.duplicates.rootsError(e.message)
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(AppStrings.duplicates.title, style = MaterialTheme.typography.titleLarge)
        directoryClassifications?.let { controller ->
            DirectoryClassificationControls(controller, choices.ollama,
                displayedSnapshot?.groups?.map { it.files }?.takeUnless { busy || scanRunning },
                classificationCache, onReloadCache = { classificationCacheRefresh++ })
        }
        OutlinedTextField(
            value = rootText,
            onValueChange = { value -> invalidateExif(); preferences.update { it.copy(duplicateRoot = value) }; groups = null },
            label = { Text(AppStrings.duplicates.folderPath) },
            enabled = !busy,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (target is ScanTarget.ChosenFolder) {
            OutlinedButton(enabled = !busy, onClick = {
                scope.launch {
                    try {
                        target.pickDirectory()?.let { path ->
                            invalidateExif()
                            preferences.update { it.copy(duplicateRoot = path) }
                            groups = null
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = AppStrings.duplicates.chooseFolderError(e.message)
                    }
                }
            }) { Text(AppStrings.duplicates.chooseFolder) }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DuplicateFileType.entries.forEach { type ->
                FilterChip(
                    selected = fileType == type,
                    onClick = { invalidateExif(); preferences.update { it.copy(duplicateFileType = type) }; groups = null },
                    enabled = !busy,
                    label = {
                        Text(when (type) {
                            DuplicateFileType.ALL -> AppStrings.duplicates.all
                            DuplicateFileType.IMAGES -> AppStrings.duplicates.images
                            DuplicateFileType.VIDEOS -> AppStrings.duplicates.videos
                            DuplicateFileType.DOCUMENTS -> AppStrings.duplicates.documents
                        })
                    },
                )
            }
        }
        OutlinedTextField(
            value = filenamePattern,
            onValueChange = { value -> invalidateExif(); preferences.update { it.copy(duplicateFilenamePattern = value) }; groups = null },
            label = { Text(AppStrings.duplicates.filenameWildcard) },
            supportingText = { Text(AppStrings.duplicates.wildcardHelp) },
            enabled = !busy,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        DuplicateSortControls(choices.duplicateSort, enabled = !busy, onChange = { transform ->
            preferences.update { it.copy(duplicateSort = transform(it.duplicateSort)) }
        })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy && rootText.isNotBlank(), onClick = {
                invalidateExif()
                val selected = rootText.trim()
                val filter = DuplicateCandidateFilter(fileType, filenamePattern)
                busy = true
                scope.launch {
                    error = null
                    groups = null
                    retention = null
                    try {
                        val root = selected.toPath().inventoryPath()
                        val results = completeDuplicateGroupsUnderRoot(repository.duplicateCandidates(filter = filter), root)
                        searchedRoot = selected
                        searchedFilter = filter
                        groups = results
                        assessResults(results)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = AppStrings.duplicates.searchError(e.message)
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (scoring) AppStrings.duplicates.scoring else if (busy) AppStrings.duplicates.searching else AppStrings.duplicates.findCandidates) }
            OutlinedButton(enabled = !busy && !queueState.isRunning && !groups.isNullOrEmpty(), onClick = {
                val snapshot = groups ?: return@OutlinedButton
                busy = true
                error = null
                scope.launch {
                    try {
                        assessResults(snapshot, force = true)
                    } finally {
                        busy = false
                    }
                }
            }) { Text(AppStrings.duplicates.recomputeScores) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                enabled = !scanRunning && !busy && !queueState.isRunning && !displayedSnapshot?.groups.isNullOrEmpty(),
                onClick = {
                    val snapshot = displayedSnapshot?.groups?.toList() ?: return@OutlinedButton
                    if (!queue.start(snapshot)) error = AppStrings.duplicates.analysisBusy
                },
            ) { Text(AppStrings.duplicates.analyzeAmbiguous) }
            if (queueState.isRunning) {
                TextButton(enabled = !cancellingQueue, onClick = {
                    cancellingQueue = true
                    scope.launch {
                        try {
                            queue.cancel()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = AppStrings.duplicates.cancellationError(e.message)
                        } finally {
                            cancellingQueue = false
                        }
                    }
                }) { Text(if (cancellingQueue) AppStrings.duplicates.cancelling else AppStrings.duplicates.cancelExif) }
            }
        }
        presentExifQueue(queueState)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        SelectionContainer(Modifier.weight(1f)) {
            Row(Modifier.fillMaxSize()) {
                LazyColumn(
                    Modifier.weight(1f).fillMaxHeight(),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (groups == null) {
                        item { Text(AppStrings.duplicates.scannedRoots) }
                        items(roots) { root ->
                            TextButton(enabled = !busy, onClick = {
                                invalidateExif()
                                preferences.update { it.copy(duplicateRoot = root.toString()) }
                                groups = null
                            }) { Text(root.toString()) }
                        }
                    }
                    if (groups != null && displayedSnapshot == null) {
                        item { Text(AppStrings.duplicates.sorting) }
                    }
                    displayedSnapshot?.let { displayed ->
                        val results = displayed.groups
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(AppStrings.duplicates.candidateCount(results.size, reportRoot), Modifier.weight(1f))
                                DisableSelection {
                                    TextButton(enabled = !copying, onClick = {
                                        val reportExif = exif
                                        val reportRecommendations = recommendations.orEmpty()
                                        copying = true
                                        copyStatus = null
                                        error = null
                                        scope.launch {
                                            try {
                                                val text = formatDuplicateResults(reportRoot, reportFilter, displayed.rules, results,
                                                    retention = reportRetention, exif = reportExif, recommendations = reportRecommendations)
                                                clipboard.setText(AnnotatedString(text))
                                                copyStatus = AppStrings.duplicates.copied(results.size)
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (e: Exception) {
                                                error = AppStrings.duplicates.copyError(e.message)
                                            } finally {
                                                copying = false
                                            }
                                        }
                                    }) { Text(if (copying) AppStrings.duplicates.copying else AppStrings.duplicates.copyResults) }
                                }
                            }
                            Text(AppStrings.duplicates.completeGroups,
                                style = MaterialTheme.typography.bodySmall)
                            Text(AppStrings.duplicates.scoreDetailsHelp,
                                style = MaterialTheme.typography.bodySmall)
                            if (exifLoading) Text(AppStrings.duplicates.loadingExif, style = MaterialTheme.typography.bodySmall)
                            copyStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                        if (results.isEmpty()) item { Text(AppStrings.duplicates.emptyCandidates) }
                        results.forEach { group ->
                            item {
                                HorizontalDivider()
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(AppStrings.duplicates.group(group.name, formatBytes(group.sizeBytes), group.files.size),
                                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                    DisableSelection {
                                        TextButton(enabled = !scanRunning && !busy && !queueState.isRunning, onClick = {
                                            if (!queue.start(listOf(group))) error = AppStrings.duplicates.analysisBusy
                                        }) { Text(AppStrings.duplicates.analyzeExif) }
                                    }
                                }
                                DuplicateRecommendationSummary(group.files.firstOrNull()?.let { recommendations?.get(it.path) })
                            }
                            items(group.files, key = { it.path.toString() }) { file ->
                                Column {
                                    FilePathLinks(file, pathActions)
                                    Text(AppStrings.duplicates.timestamps(formatEpochMillis(file.createdAt), formatEpochMillis(file.modifiedAt)),
                                        style = MaterialTheme.typography.bodySmall)
                                    RetentionScoreLine(file.path, reportRetention[file.path])
                                    FileRecommendationLine(file.path, recommendations?.get(file.path)?.files?.get(file.path),
                                        classificationCache.records[file.directoryPath.inventoryPath()])
                                    ExifMetadataLine(file.path, exif[file.path])
                                }
                            }
                        }
                    }
                }
                DisableSelection {
                    ResultsScrollbar(listState, Modifier.fillMaxHeight())
                }
            }
        }
    }
}
