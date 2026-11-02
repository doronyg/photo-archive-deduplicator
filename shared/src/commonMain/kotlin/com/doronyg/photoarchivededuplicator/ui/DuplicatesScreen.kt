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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.doronyg.photoarchivededuplicator.scan.DirectoryRepository
import com.doronyg.photoarchivededuplicator.PathActions
import com.doronyg.photoarchivededuplicator.UiPreferences
import com.doronyg.photoarchivededuplicator.retention.RetentionAssessmentResult
import com.doronyg.photoarchivededuplicator.retention.RetentionScoreService
import com.doronyg.photoarchivededuplicator.retention.RetentionPresentation
import com.doronyg.photoarchivededuplicator.retention.sortDuplicateFilesByRetention
import com.doronyg.photoarchivededuplicator.scan.DuplicateCandidateGroup
import com.doronyg.photoarchivededuplicator.scan.DuplicateCandidateFilter
import com.doronyg.photoarchivededuplicator.scan.DuplicateFileType
import com.doronyg.photoarchivededuplicator.scan.DuplicateSortRule
import com.doronyg.photoarchivededuplicator.scan.sortDuplicateCandidates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import okio.Path
import okio.Path.Companion.toPath

private data class SortedCandidates(
    val source: List<DuplicateCandidateGroup>,
    val rules: List<DuplicateSortRule>,
    val retention: Map<Path, RetentionPresentation>,
    val groups: List<DuplicateCandidateGroup>,
)

@Composable
fun DuplicatesScreen(repository: DirectoryRepository, target: ScanTarget, pathActions: PathActions, preferences: UiPreferences, retentionScores: RetentionScoreService, modifier: Modifier = Modifier) {
    val choices by preferences.state.collectAsState()
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
    var retention by remember { mutableStateOf<RetentionAssessmentResult?>(null) }
    var copying by remember { mutableStateOf(false) }
    var copyStatus by remember(groups, choices.duplicateSort, retention) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()
    val reportRetention = retention?.files.orEmpty()
    val sorted by produceState<SortedCandidates?>(null, groups, choices.duplicateSort, reportRetention) {
        value = null
        val source = groups
        val rules = choices.duplicateSort
        val scores = reportRetention
        if (source != null) {
            val orderedGroups = sortDuplicateCandidates(source, rules)
            value = SortedCandidates(source, rules, scores, sortDuplicateFilesByRetention(orderedGroups, scores))
        }
    }
    val displayedSnapshot = sorted?.takeIf {
        it.source === groups && it.rules == choices.duplicateSort && it.retention === reportRetention
    }
    val reportRoot = searchedRoot
    val reportFilter = searchedFilter

    suspend fun assessResults(candidates: List<DuplicateCandidateGroup>, force: Boolean = false) {
        scoring = true
        try {
            val result = retentionScores.assess(candidates, force)
            retention = result
            if (result.invalidatedFiles > 0) {
                error = "Inventory changed during scoring. Search again to refresh the affected files."
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = "Could not calculate retention scores: ${e.message}"
        } finally {
            scoring = false
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
            error = "Could not load roots: ${e.message}"
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Duplicate candidates", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = rootText,
            onValueChange = { value -> preferences.update { it.copy(duplicateRoot = value) }; groups = null },
            label = { Text("Search under absolute folder path") },
            enabled = !busy,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (target is ScanTarget.ChosenFolder) {
            OutlinedButton(enabled = !busy, onClick = {
                scope.launch {
                    try {
                        target.pickDirectory()?.let { path ->
                            preferences.update { it.copy(duplicateRoot = path) }
                            groups = null
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = "Could not choose folder: ${e.message}"
                    }
                }
            }) { Text("Choose folder…") }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DuplicateFileType.entries.forEach { type ->
                FilterChip(
                    selected = fileType == type,
                    onClick = { preferences.update { it.copy(duplicateFileType = type) }; groups = null },
                    enabled = !busy,
                    label = {
                        Text(when (type) {
                            DuplicateFileType.ALL -> "All"
                            DuplicateFileType.IMAGES -> "Images"
                            DuplicateFileType.VIDEOS -> "Videos"
                            DuplicateFileType.DOCUMENTS -> "Documents"
                        })
                    },
                )
            }
        }
        OutlinedTextField(
            value = filenamePattern,
            onValueChange = { value -> preferences.update { it.copy(duplicateFilenamePattern = value) }; groups = null },
            label = { Text("Filename wildcard (optional)") },
            supportingText = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Case-insensitive, e.g. IMG*.jpg. * = any text, ? = one character; dot is literal. Types use file extensions.")
                    Text("Same exact name (case-sensitive) and size only — not verified duplicates. Times are evidence, not a filter. No files are changed.")
                }
            },
            enabled = !busy,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        DuplicateSortControls(choices.duplicateSort, enabled = !busy, onChange = { transform ->
            preferences.update { it.copy(duplicateSort = transform(it.duplicateSort)) }
        })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy && rootText.isNotBlank(), onClick = {
                val selected = rootText.trim()
                val filter = DuplicateCandidateFilter(fileType, filenamePattern)
                busy = true
                scope.launch {
                    error = null
                    groups = null
                    retention = null
                    try {
                        val results = repository.duplicateCandidates(selected.toPath(), filter)
                        searchedRoot = selected
                        searchedFilter = filter
                        groups = results
                        assessResults(results)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = "Search failed: ${e.message}"
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (scoring) "Scoring..." else if (busy) "Searching…" else "Find candidates") }
            OutlinedButton(enabled = !busy && !groups.isNullOrEmpty(), onClick = {
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
            }) { Text("Recompute scores") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        SelectionContainer(Modifier.weight(1f)) {
            Row(Modifier.fillMaxSize()) {
                LazyColumn(
                    Modifier.weight(1f).fillMaxHeight(),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (groups == null) {
                        item { Text("Previously scanned roots (select or enter any subtree):") }
                        items(roots) { root ->
                            TextButton(enabled = !busy, onClick = {
                                preferences.update { it.copy(duplicateRoot = root.toString()) }
                            }) { Text(root.toString()) }
                        }
                    }
                    if (groups != null && displayedSnapshot == null) {
                        item { Text("Sorting candidates...") }
                    }
                    displayedSnapshot?.let { displayed ->
                        val results = displayed.groups
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${results.size} candidate groups under $reportRoot.", Modifier.weight(1f))
                                DisableSelection {
                                    TextButton(enabled = !copying, onClick = {
                                        copying = true
                                        copyStatus = null
                                        error = null
                                        scope.launch {
                                            try {
                                                val text = formatDuplicateResults(reportRoot, reportFilter, displayed.rules, results,
                                                    retention = reportRetention)
                                                clipboard.setText(AnnotatedString(text))
                                                copyStatus = "Copied ${results.size} groups, including full paths. Review before sharing."
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (e: Exception) {
                                                error = "Could not copy results: ${e.message}"
                                            } finally {
                                                copying = false
                                            }
                                        }
                                    }) { Text(if (copying) "Copying..." else "Copy results") }
                                }
                            }
                            Text("Sort changes apply immediately; search again after changing inventory or exclusions.",
                                style = MaterialTheme.typography.bodySmall)
                            Text("Highest retention score first within each group. Scores are advisory, not content verification. Tap a score for its factors.",
                                style = MaterialTheme.typography.bodySmall)
                            copyStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                        if (results.isEmpty()) item { Text("No candidate groups match these filters in the stored inventory.") }
                        results.forEach { group ->
                            item {
                                HorizontalDivider()
                                Text("${group.name} · ${formatBytes(group.sizeBytes)} · ${group.files.size} files",
                                    style = MaterialTheme.typography.titleMedium)
                            }
                            items(group.files, key = { it.path.toString() }) { file ->
                                Column {
                                    FilePathLinks(file, pathActions)
                                    Text("Created ${formatEpochMillis(file.createdAt)} · modified ${formatEpochMillis(file.modifiedAt)}",
                                        style = MaterialTheme.typography.bodySmall)
                                    RetentionScoreLine(file.path, reportRetention[file.path])
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
