package com.doronyg.photoarchivededuplicator.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.doronyg.photoarchivededuplicator.Diagnostics
import com.doronyg.photoarchivededuplicator.PathActions
import com.doronyg.photoarchivededuplicator.diagnosticLogDirectory
import com.doronyg.photoarchivededuplicator.LogLevel
import com.doronyg.photoarchivededuplicator.LogEntry
import com.doronyg.photoarchivededuplicator.formatLog
import com.doronyg.photoarchivededuplicator.formatLogs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
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
fun LogsScreen(pathActions: PathActions, modifier: Modifier = Modifier) {
    val snapshot by Diagnostics.logger.state.collectAsState()
    var filter by remember { mutableStateOf("") }
    var minimum by remember { mutableStateOf(LogLevel.D) }
    var follow by remember { mutableStateOf(true) }
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
        Text("Local diagnostics • epoch milliseconds • ${visible.size} retained/visible")
        Text("Dropped: ${snapshot.dropped} • native sink dropped: ${snapshot.sinkDropped} • sink failures: ${snapshot.sinkFailures}")
        logDirectory.getOrNull()?.let { directory ->
            PathLink("Open local log folder: $directory", action = { pathActions.openFolder(directory.toPath()) })
            Text("Files: 5 × 5 MiB maximum • file queue dropped: ${snapshot.fileDropped} • file failures: ${snapshot.fileFailures}")
            Text("Flushed per entry; queued logs may be lost on force-kill. Clear affects only this view.")
            snapshot.fileError?.let { Text("Last file error: $it", color = MaterialTheme.colorScheme.error) }
        }
        logDirectory.exceptionOrNull()?.let {
            Text("Could not resolve log folder: ${it.message}", color = MaterialTheme.colorScheme.error)
        }
        OutlinedTextField(filter, { filter = it }, label = { Text("Filter tag, message or thread") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            LogLevel.entries.forEach { level ->
                FilterChip(selected = minimum == level, onClick = { minimum = level }, label = { Text("${level.name}+") })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { Diagnostics.logger.clear() }) { Text("Clear") }
            TextButton(onClick = {
                val captured = visible
                scope.launch {
                    val text = withContext(Dispatchers.Default) { formatLogs(captured) }
                    clipboard.setText(AnnotatedString(text))
                }
            }) { Text("Copy all visible") }
            TextButton(onClick = { follow = !follow }) { Text(if (follow) "Pause scroll" else "Follow") }
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
