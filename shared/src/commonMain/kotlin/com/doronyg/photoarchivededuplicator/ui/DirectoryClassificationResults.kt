package com.doronyg.photoarchivededuplicator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationContext
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal data class DirectoryClassificationResultsState(
    val records: List<DirectoryClassificationRecord> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

internal fun filterDirectoryClassificationResults(
    records: List<DirectoryClassificationRecord>,
    context: DirectoryClassificationContext?,
    currentOnly: Boolean,
    query: String,
): List<DirectoryClassificationRecord> {
    val search = query.trim()
    return records.filter { record ->
        (!currentOnly || record.context == context) &&
            (search.isEmpty() || record.directoryPath.toString().contains(search, ignoreCase = true) ||
                record.classification.name.contains(search, ignoreCase = true) ||
                record.context.model.contains(search, ignoreCase = true))
    }
}

internal suspend fun loadDirectoryClassificationResults(
    load: suspend () -> List<DirectoryClassificationRecord>,
): DirectoryClassificationResultsState = try {
    DirectoryClassificationResultsState(records = load(), loading = false)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    DirectoryClassificationResultsState(loading = false,
        error = "Could not load saved directory classifications: ${e.message}")
}

@Composable
internal fun DirectoryClassificationResults(
    load: suspend () -> List<DirectoryClassificationRecord>,
    context: DirectoryClassificationContext?,
    revision: Long,
) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) "Hide saved directory results" else "View saved directory results")
    }
    if (!expanded) return
    var currentOnly by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var refresh by remember { mutableStateOf(0) }
    var copying by remember { mutableStateOf(false) }
    var copyStatus by remember { mutableStateOf<String?>(null) }
    var copyError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()
    val state by produceState(DirectoryClassificationResultsState(), load, revision, refresh) {
        value = DirectoryClassificationResultsState()
        value = loadDirectoryClassificationResults(load)
    }
    val visible = remember(state.records, context, currentOnly, query) {
        filterDirectoryClassificationResults(state.records, context, currentOnly, query)
    }
    val loadError = state.error
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Saved directory classifications", style = MaterialTheme.typography.titleMedium)
        Text("Database only; viewing or reloading results never contacts Ollama. " +
            "Only matching server, model and prompt-version results are eligible for recommendations.",
            style = MaterialTheme.typography.bodySmall)
        Text("AI labels influence group retention recommendations, not individual retention scores or duplicate confidence.",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = currentOnly, onClick = { currentOnly = true }, label = { Text("Current saved settings") })
            FilterChip(selected = !currentOnly, onClick = { currentOnly = false }, label = { Text("All saved results") })
        }
        context?.let {
            Text("Current: ${quoteResultText(it.baseUrl)} / ${quoteResultText(it.model)} / prompt v${it.promptVersion}",
                style = MaterialTheme.typography.bodySmall)
        } ?: Text("No valid saved Ollama settings. Choose All saved results to inspect earlier classifications.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(query, { query = it }, label = { Text("Filter directory, classification or model") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !state.loading, onClick = { refresh++ }) { Text("Reload saved results") }
            OutlinedButton(enabled = !copying && !state.loading && loadError == null && visible.isNotEmpty(),
                onClick = {
                    val records = visible.toList()
                    val savedContext = context
                    val selectedScope = currentOnly
                    val selectedQuery = query
                    copying = true
                    copyStatus = null
                    copyError = null
                    scope.launch {
                        try {
                            val text = formatDirectoryClassificationResults(records, savedContext, selectedScope, selectedQuery)
                            clipboard.setText(AnnotatedString(text))
                            copyStatus = "Copied ${records.size} saved results from the selected filters, including off-screen rows. Review before sharing."
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            copyError = "Could not copy saved directory results: ${e.message}"
                        } finally {
                            copying = false
                        }
                    }
                }) { Text(if (copying) "Copying..." else "Copy results") }
        }
        copyStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        copyError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        when {
            state.loading -> Text("Loading saved classifications...")
            loadError != null -> Text(loadError, color = MaterialTheme.colorScheme.error)
            else -> {
                Text("${visible.size} shown; ${state.records.size} saved results.")
                if (visible.isEmpty()) Text("No saved classifications match this selection.")
                else Row(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    LazyColumn(Modifier.weight(1f).fillMaxHeight(), state = listState,
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(visible) { record ->
                            Column {
                                Text("${record.classification.name} · ${quoteResultText(record.directoryPath.toString())}")
                                Text("${formatEpochMillis(record.classifiedAt)} UTC · model ${quoteResultText(record.context.model)} · " +
                                    "prompt v${record.context.promptVersion}", style = MaterialTheme.typography.bodySmall)
                                Text("Server: ${quoteResultText(record.context.baseUrl)} · " +
                                    if (record.context == context) "Matches current saved settings"
                                    else "Not used by current saved settings",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    ResultsScrollbar(listState, Modifier.fillMaxHeight())
                }
            }
        }
    }
}
