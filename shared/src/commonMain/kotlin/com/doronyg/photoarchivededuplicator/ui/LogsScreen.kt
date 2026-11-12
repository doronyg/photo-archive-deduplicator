package com.doronyg.photoarchivededuplicator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.doronyg.photoarchivededuplicator.Diagnostics
import com.doronyg.photoarchivededuplicator.LogEntry
import com.doronyg.photoarchivededuplicator.LogLevel
import com.doronyg.photoarchivededuplicator.PathActions
import com.doronyg.photoarchivededuplicator.UiChoices
import com.doronyg.photoarchivededuplicator.UiPreferences
import com.doronyg.photoarchivededuplicator.diagnosticLogDirectory
import com.doronyg.photoarchivededuplicator.formatLog
import com.doronyg.photoarchivededuplicator.formatLogs
import com.doronyg.photoarchivededuplicator.strings.AppStrings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath

internal suspend fun filterLogEntries(
    entries: List<LogEntry>,
    filter: String,
    minimum: LogLevel,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): List<LogEntry> = withContext(dispatcher) {
    val context = currentCoroutineContext()
    entries.filter {
        context.ensureActive()
        it.level >= minimum && (filter.isBlank() ||
            it.tag.contains(filter, ignoreCase = true) || it.message.contains(filter, ignoreCase = true) ||
            it.thread.contains(filter, ignoreCase = true))
    }
}

@Composable
fun LogsScreen(pathActions: PathActions, modifier: Modifier = Modifier, preferences: UiPreferences? = null) {
    val snapshot by Diagnostics.logger.state.collectAsState()
    var transientChoices by remember { mutableStateOf(UiChoices()) }
    val choices = preferences?.state?.collectAsState()?.value ?: transientChoices
    val updateChoices: ((UiChoices) -> UiChoices) -> Unit = { transform ->
        if (preferences != null) preferences.update(transform)
        else transientChoices = transform(transientChoices)
    }
    val filter = choices.logFilter
    val minimum = choices.logMinimum
    val follow = choices.logFollow
    val visible by produceState(emptyList<LogEntry>(), snapshot.entries, filter, minimum) {
        value = filterLogEntries(snapshot.entries, filter, minimum)
    }
    val list = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val logDirectory = remember { runCatching { diagnosticLogDirectory() } }
    LaunchedEffect(visible.lastOrNull()?.id, follow) {
        if (follow && visible.isNotEmpty()) list.scrollToItem(visible.lastIndex)
    }
    Column(modifier) {
        Text(AppStrings.inventory.logSummary(visible.size))
        Text(AppStrings.inventory.logSinkStatus(snapshot.dropped, snapshot.sinkDropped, snapshot.sinkFailures))
        logDirectory.getOrNull()?.let { directory ->
            PathLink(AppStrings.inventory.openLogFolder(directory), action = { pathActions.openFolder(directory.toPath()) })
            Text(AppStrings.inventory.logFileStatus(snapshot.fileDropped, snapshot.fileFailures))
            Text(AppStrings.inventory.logPersistenceNotice)
            snapshot.fileError?.let { Text(AppStrings.inventory.lastLogFileError(it), color = MaterialTheme.colorScheme.error) }
        }
        logDirectory.exceptionOrNull()?.let {
            Text(AppStrings.inventory.resolveLogFolderFailed(it.message), color = MaterialTheme.colorScheme.error)
        }
        OutlinedTextField(filter, { value -> updateChoices { it.copy(logFilter = value) } }, label = { Text(AppStrings.inventory.logFilter) },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            LogLevel.entries.forEach { level ->
                FilterChip(selected = minimum == level, onClick = { updateChoices { it.copy(logMinimum = level) } }, label = { Text(AppStrings.inventory.minimumLogLevel(level.name)) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { Diagnostics.logger.clear() }) { Text(AppStrings.inventory.clearLogs) }
            TextButton(onClick = {
                val captured = visible
                scope.launch {
                    val text = withContext(Dispatchers.Default) { formatLogs(captured) }
                    clipboard.setText(AnnotatedString(text))
                }
            }) { Text(AppStrings.inventory.copyVisibleLogs) }
            TextButton(onClick = { updateChoices { it.copy(logFollow = !it.logFollow) } }) { Text(if (follow) AppStrings.inventory.pauseScroll else AppStrings.inventory.followLogs) }
        }
        SelectionContainer(Modifier.weight(1f)) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                items(visible, key = { it.id }) { entry ->
                    Text(formatLog(entry), fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
                }
            }
        }
    }
}
