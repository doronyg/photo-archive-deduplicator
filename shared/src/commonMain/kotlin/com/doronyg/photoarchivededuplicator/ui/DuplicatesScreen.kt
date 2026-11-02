package com.doronyg.photoarchivededuplicator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.doronyg.photoarchivededuplicator.scan.DirectoryRepository
import com.doronyg.photoarchivededuplicator.PathActions
import com.doronyg.photoarchivededuplicator.scan.DuplicateCandidateGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import okio.Path
import okio.Path.Companion.toPath

@Composable
fun DuplicatesScreen(repository: DirectoryRepository, target: ScanTarget, pathActions: PathActions, modifier: Modifier = Modifier) {
    var rootText by rememberSaveable { mutableStateOf((target as? ScanTarget.Fixed)?.root?.toString().orEmpty()) }
    var roots by remember { mutableStateOf(emptyList<Path>()) }
    var groups by remember { mutableStateOf<List<DuplicateCandidateGroup>?>(null) }
    var searchedRoot by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(repository) {
        try {
            roots = repository.childOverviews(null).map { it.directory.path }
            if (rootText.isBlank()) rootText = roots.firstOrNull()?.toString().orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = "Could not load roots: ${e.message}"
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Duplicate candidates", style = MaterialTheme.typography.titleLarge)
        Text("Same exact name (case-sensitive) and size only — not verified duplicates. Times are evidence, not a filter. No files are changed.")
        OutlinedTextField(
            value = rootText,
            onValueChange = { rootText = it; groups = null },
            label = { Text("Search under absolute folder path") },
            enabled = !busy,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy && rootText.isNotBlank(), onClick = {
                val selected = rootText.trim()
                scope.launch {
                    busy = true
                    error = null
                    groups = null
                    try {
                        groups = repository.duplicateCandidates(selected.toPath())
                        searchedRoot = selected
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = "Search failed: ${e.message}"
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (busy) "Searching…" else "Find candidates") }
            if (target is ScanTarget.ChosenFolder) {
                OutlinedButton(enabled = !busy, onClick = {
                    scope.launch {
                        try {
                            target.pickDirectory()?.let { rootText = it; groups = null }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = "Could not choose folder: ${e.message}"
                        }
                    }
                }) { Text("Choose folder…") }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (groups == null) {
                item { Text("Previously scanned roots (select or enter any subtree):") }
                items(roots) { root ->
                    TextButton(enabled = !busy, onClick = { rootText = root.toString() }) { Text(root.toString()) }
                }
            }
            groups?.let { results ->
                item { Text("${results.size} candidate groups under $searchedRoot. Search again after changing inventory or exclusions.") }
                if (results.isEmpty()) item { Text("No candidate groups found in the stored inventory.") }
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
                        }
                    }
                }
            }
        }
    }
}
