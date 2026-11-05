package com.doronyg.photoarchivededuplicator.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.doronyg.photoarchivededuplicator.PathActions
import com.doronyg.photoarchivededuplicator.UiPreferences
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationController
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationJobState
import com.doronyg.photoarchivededuplicator.exif.ExifQueueController
import com.doronyg.photoarchivededuplicator.exif.ExifQueueState
import com.doronyg.photoarchivededuplicator.exif.ExifQueuePhase
import com.doronyg.photoarchivededuplicator.exif.ExifRecord
import com.doronyg.photoarchivededuplicator.scan.DirectoryRepository
import com.doronyg.photoarchivededuplicator.scan.SUSPECTED_IMAGE_MIN_SIZE_BYTES
import com.doronyg.photoarchivededuplicator.scan.SuspectedDuplicateGroup
import com.doronyg.photoarchivededuplicator.scan.SuspectedDuplicateFilter
import com.doronyg.photoarchivededuplicator.scan.filterSuspectedDuplicateCandidates
import com.doronyg.photoarchivededuplicator.scan.inventoryPath
import com.doronyg.photoarchivededuplicator.scan.DuplicateSortRule
import com.doronyg.photoarchivededuplicator.scan.DuplicateSortCriterion
import com.doronyg.photoarchivededuplicator.scan.sortSuspectedDuplicateCandidates
import com.doronyg.photoarchivededuplicator.recommendation.DuplicateGroupRecommendation
import com.doronyg.photoarchivededuplicator.recommendation.RecommendationAction
import com.doronyg.photoarchivededuplicator.retention.RetentionScoreService
import com.doronyg.photoarchivededuplicator.retention.RetentionPresentation
import com.doronyg.photoarchivededuplicator.retention.sortSuspectedFilesByRetention
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okio.Path
import com.doronyg.photoarchivededuplicator.strings.AppStrings
import okio.Path.Companion.toPath

private data class SortedSuspectedCandidates(
    val source: List<SuspectedDuplicateGroup>,
    val rules: List<DuplicateSortRule>,
    val filter: SuspectedDuplicateFilter,
    val recommendations: Map<Path, DuplicateGroupRecommendation>?,
    val groups: List<SuspectedDuplicateGroup>,
)

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
    preferences: UiPreferences,
    scanning: Boolean,
    modifier: Modifier = Modifier,
    directoryClassifications: DirectoryClassificationController? = null,
) {
    val choices by preferences.state.collectAsState()
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
    var filtersExpanded by remember { mutableStateOf(false) }
    var copying by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val filterResult = remember(choices.suspectedDuplicateRoot, choices.suspectedDuplicateFilenamePattern,
        choices.suspectedDuplicateRecommendation) {
        try {
            Result.success(SuspectedDuplicateFilter(
                choices.suspectedDuplicateRoot.trim().takeIf { it.isNotEmpty() }?.toPath()?.inventoryPath(),
                choices.suspectedDuplicateFilenamePattern,
                choices.suspectedDuplicateRecommendation,
            ))
        } catch (e: IllegalArgumentException) {
            Result.failure<SuspectedDuplicateFilter>(e)
        }
    }
    val filter = filterResult.getOrNull()
    val recommendationGroups = remember(groups) { groups.map { it.files } }
    val classificationState = directoryClassifications?.state?.collectAsState()?.value ?: DirectoryClassificationJobState()
    val classificationContext = remember(choices.ollama) { runCatching { choices.ollama.context() }.getOrNull() }
    var classificationCacheRefresh by remember { mutableStateOf(0) }
    val classificationCache = rememberCachedDirectoryClassifications(repository, recommendationGroups,
        classificationContext, classificationState.revision, classificationCacheRefresh)
    val recommendations = rememberDuplicateRecommendations(recommendationGroups, exif, scores,
        evidenceReady = classificationCache.ready && !loading && !scanning && !queueState.isRunning && loadedRevision == queueState.revision,
        directoryClassifications = classificationCache.records)
    val sortRecommendations = recommendations.takeIf {
        filter?.recommendation != null ||
            choices.suspectedDuplicateSort.any { rule -> rule.enabled && rule.criterion == DuplicateSortCriterion.RECOMMENDATION }
    }
    val sorted by produceState<SortedSuspectedCandidates?>(null, groups, filter, choices.suspectedDuplicateSort, sortRecommendations) {
        value = null
        val source = groups
        val rules = choices.suspectedDuplicateSort
        val advice = sortRecommendations
        val selected = filter
        if (selected != null && (selected.recommendation == null || advice != null)) {
            val matches = filterSuspectedDuplicateCandidates(source, selected, advice.orEmpty())
            value = SortedSuspectedCandidates(source, rules, selected, advice,
                sortSuspectedDuplicateCandidates(matches, rules, recommendations = advice.orEmpty()))
        }
    }
    val displayedSnapshot = sorted?.takeIf {
        it.source === groups && it.filter == filter && it.rules == choices.suspectedDuplicateSort &&
            it.recommendations === sortRecommendations
    }
    var copyStatus by remember(groups, filter, choices.suspectedDuplicateSort, scores, exif, recommendations) {
        mutableStateOf<String?>(null)
    }
    val filtersActive = choices.suspectedDuplicateRoot.isNotBlank() ||
        choices.suspectedDuplicateFilenamePattern.trim().let { it.isNotEmpty() && it != "*" } ||
        choices.suspectedDuplicateRecommendation != null

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
                    error = AppStrings.duplicates.suspectedChanged
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (request == generation) error = AppStrings.duplicates.suspectedError(e.message)
        } finally {
            if (request == generation) loading = false
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(AppStrings.duplicates.suspectedTitle, style = MaterialTheme.typography.titleLarge)
        directoryClassifications?.let { controller ->
            DirectoryClassificationControls(controller, choices.ollama,
                displayedSnapshot?.groups?.map { it.files }?.takeUnless { loading || scanning },
                classificationCache, onReloadCache = { classificationCacheRefresh++ })
        }
        Text(
            AppStrings.duplicates.suspectedScope(SUSPECTED_IMAGE_MIN_SIZE_BYTES),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { filtersExpanded = !filtersExpanded }) {
                Text(if (filtersExpanded) AppStrings.duplicates.hideFilters else if (filtersActive) AppStrings.duplicates.activeFilters else AppStrings.duplicates.filters)
            }
            OutlinedButton(enabled = !scanning && !queueState.isRunning, onClick = {
                error = null
                if (!queue.startSuspected()) error = AppStrings.duplicates.analysisBusy
            }) { Text(AppStrings.duplicates.analyzeSuspected) }
            OutlinedButton(enabled = !loading && !queueState.isRunning, onClick = { refresh++ }) {
                Text(AppStrings.duplicates.refreshMatches)
            }
            OutlinedButton(enabled = !loading && !scanning && !queueState.isRunning && groups.isNotEmpty(), onClick = {
                forceNextRefresh = true
                refresh++
            }) { Text(AppStrings.duplicates.recomputeScores) }
            if (queueState.isRunning) {
                TextButton(enabled = !cancelling, onClick = {
                    cancelling = true
                    scope.launch {
                        try {
                            queue.cancel()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = AppStrings.duplicates.cancellationError(e.message)
                        } finally {
                            cancelling = false
                        }
                    }
                }) { Text(if (cancelling) AppStrings.duplicates.cancelling else AppStrings.duplicates.cancelExif) }
            }
        }
        if (filtersExpanded) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = choices.suspectedDuplicateRoot,
                    onValueChange = { value -> preferences.update { it.copy(suspectedDuplicateRoot = value) } },
                    label = { Text(AppStrings.duplicates.optionalFolderPath) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = choices.suspectedDuplicateFilenamePattern,
                    onValueChange = { value -> preferences.update { it.copy(suspectedDuplicateFilenamePattern = value) } },
                    label = { Text(AppStrings.duplicates.filenameWildcard) },
                    supportingText = { Text(AppStrings.duplicates.wildcardHelp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = choices.suspectedDuplicateRecommendation == null,
                        onClick = { preferences.update { it.copy(suspectedDuplicateRecommendation = null) } },
                        label = { Text(AppStrings.duplicates.allRecommendations) })
                    RecommendationAction.entries.forEach { action ->
                        FilterChip(selected = choices.suspectedDuplicateRecommendation == action,
                            onClick = { preferences.update { it.copy(suspectedDuplicateRecommendation = action) } },
                            label = { Text(recommendationActionLabel(action)) })
                    }
                }
                Text(AppStrings.duplicates.suspectedFilterHelp,
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    preferences.update { it.copy(suspectedDuplicateRoot = "", suspectedDuplicateFilenamePattern = "",
                        suspectedDuplicateRecommendation = null) }
                }) { Text(AppStrings.duplicates.clearFilters) }
            }
        }
        presentExifQueue(queueState)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        DuplicateSortControls(choices.suspectedDuplicateSort, enabled = !loading, onChange = { transform ->
            preferences.update { it.copy(suspectedDuplicateSort = transform(it.suspectedDuplicateSort)) }
        })
        if (queueState.isRunning || scanning) {
            Text(AppStrings.duplicates.processing,
                style = MaterialTheme.typography.bodySmall)
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        filterResult.exceptionOrNull()?.let { Text(AppStrings.duplicates.filterError(it.message), color = MaterialTheme.colorScheme.error) }
        SelectionContainer(Modifier.weight(1f)) {
            Row(Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(when {
                                loading -> AppStrings.duplicates.loadingMatches
                                filter == null -> AppStrings.duplicates.correctFilter
                                displayedSnapshot == null -> AppStrings.duplicates.filtering
                                else -> AppStrings.duplicates.matchCount(displayedSnapshot.groups.size, groups.size)
                            }, modifier = Modifier.weight(1f))
                            DisableSelection {
                                TextButton(enabled = !copying && !loading && displayedSnapshot != null, onClick = {
                                    val displayed = displayedSnapshot ?: return@TextButton
                                    val reportScores = scores
                                    val reportExif = exif
                                    val reportRecommendations = recommendations.orEmpty()
                                    copying = true
                                    copyStatus = null
                                    error = null
                                    scope.launch {
                                        try {
                                            val text = formatSuspectedDuplicateResults(displayed.filter, displayed.rules,
                                                displayed.groups, retention = reportScores, exif = reportExif,
                                                recommendations = reportRecommendations)
                                            clipboard.setText(AnnotatedString(text))
                                            copyStatus = AppStrings.duplicates.copied(displayed.groups.size)
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
                        copyStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    if (!loading && groups.isEmpty()) {
                        item { Text(AppStrings.duplicates.emptyMatches) }
                    }
                    if (!loading && groups.isNotEmpty() && displayedSnapshot?.groups?.isEmpty() == true) {
                        item { Text(AppStrings.duplicates.emptyFilteredMatches) }
                    }
                    displayedSnapshot?.groups.orEmpty().forEach { group ->
                        item {
                            HorizontalDivider()
                            Text(
                                AppStrings.duplicates.captured(group.capturedAt, group.offsetOriginal ?: AppStrings.duplicates.timezoneUnknown),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(AppStrings.duplicates.sizedGroup(group.sizeBytes, formatBytes(group.sizeBytes), group.files.size))
                            DuplicateRecommendationSummary(group.files.firstOrNull()?.let { recommendations?.get(it.path) })
                        }
                        items(group.files, key = { it.path.toString() }) { file ->
                            Column {
                                FilePathLinks(file, pathActions)
                                Text(
                                    AppStrings.duplicates.timestamps(formatEpochMillis(file.createdAt), formatEpochMillis(file.modifiedAt)),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                RetentionScoreLine(file.path, scores[file.path])
                                FileRecommendationLine(file.path, recommendations?.get(file.path)?.files?.get(file.path),
                                    classificationCache.records[file.directoryPath.inventoryPath()])
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
