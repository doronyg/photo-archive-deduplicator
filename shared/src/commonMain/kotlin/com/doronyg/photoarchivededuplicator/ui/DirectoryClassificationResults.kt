package com.doronyg.photoarchivededuplicator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationContext
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationRecord
import com.doronyg.photoarchivededuplicator.strings.AppStrings
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
        error = AppStrings.analysis.loadResultsError(e.message))
}

@Composable
internal fun DirectoryClassificationResults(
    load: suspend () -> List<DirectoryClassificationRecord>,
    context: DirectoryClassificationContext?,
    revision: Long,
) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) AppStrings.analysis.hideSavedResults else AppStrings.analysis.viewSavedResults)
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
        Text(AppStrings.analysis.savedResultsTitle, style = MaterialTheme.typography.titleMedium)
        Text(AppStrings.analysis.profileEligibility,
            style = MaterialTheme.typography.bodySmall)
        Text(AppStrings.analysis.labelInfluence, style = MaterialTheme.typography.bodySmall)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = currentOnly, onClick = { currentOnly = true }, label = { Text(AppStrings.analysis.currentSettings) })
            FilterChip(selected = !currentOnly, onClick = { currentOnly = false }, label = { Text(AppStrings.analysis.allResults) })
        }
        context?.let {
            Text(AppStrings.analysis.currentProfile(quoteResultText(it.baseUrl), quoteResultText(it.model), it.promptVersion),
                style = MaterialTheme.typography.bodySmall)
        } ?: Text(AppStrings.analysis.noCurrentSettings,
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(query, { query = it }, label = { Text(AppStrings.analysis.filterResults) },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !state.loading, onClick = { refresh++ }) { Text(AppStrings.refresh) }
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
                            copyStatus = AppStrings.analysis.copiedResults(records.size)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            copyError = AppStrings.analysis.copyResultsError(e.message)
                        } finally {
                            copying = false
                        }
                    }
                }) { Text(if (copying) AppStrings.analysis.copying else AppStrings.analysis.copyResults) }
        }
        copyStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        copyError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        when {
            state.loading -> Text(AppStrings.loading)
            loadError != null -> Text(loadError, color = MaterialTheme.colorScheme.error)
            else -> {
                Text(AppStrings.analysis.resultCounts(visible.size, state.records.size))
                if (visible.isEmpty()) Text(AppStrings.analysis.noMatchingResults)
                else Row(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    LazyColumn(Modifier.weight(1f).fillMaxHeight(), state = listState,
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(visible) { record ->
                            Column {
                                Text(AppStrings.analysis.savedLabel(record.classification, quoteResultText(record.directoryPath.toString())))
                                Text(AppStrings.analysis.savedProfile(formatEpochMillis(record.classifiedAt),
                                    quoteResultText(record.context.model), record.context.promptVersion),
                                    style = MaterialTheme.typography.bodySmall)
                                Text(AppStrings.analysis.savedServer(quoteResultText(record.context.baseUrl), record.context == context),
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
