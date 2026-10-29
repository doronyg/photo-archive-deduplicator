package com.doronyg.photoarchivededuplicator.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.doronyg.photoarchivededuplicator.scan.DirectoryOverview
import com.doronyg.photoarchivededuplicator.scan.DirectoryRepository
import com.doronyg.photoarchivededuplicator.scan.DirectoryStatus
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import com.doronyg.photoarchivededuplicator.scan.InventorySummary
import okio.Path

private data class BrowseContent(
    val summary: InventorySummary,
    val directories: List<DirectoryOverview>,
    val files: List<FileRecord>,
)

/** Read-only view of the inventory: drill down from the scan roots. */
@Composable
fun BrowseScreen(repository: DirectoryRepository, modifier: Modifier = Modifier) {
    // Empty stack = list of scan roots
    var stack by remember { mutableStateOf(listOf<Path>()) }
    var refreshCount by remember { mutableIntStateOf(0) }
    var content by remember { mutableStateOf<BrowseContent?>(null) }
    val current = stack.lastOrNull()

    LaunchedEffect(current, refreshCount) {
        content = BrowseContent(
            summary = repository.summary(),
            directories = repository.childOverviews(current),
            files = current?.let { repository.filesIn(it) }.orEmpty(),
        )
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            content?.summary?.let { SummaryLine(it, Modifier.weight(1f)) }
            OutlinedButton(onClick = { refreshCount++ }) { Text("Refresh") }
        }
        Breadcrumbs(stack, onNavigate = { depth -> stack = stack.take(depth) })
        HorizontalDivider()
        content?.let { browse ->
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                if (browse.directories.isEmpty() && browse.files.isEmpty()) {
                    item { Text(if (current == null) "Nothing scanned yet." else "Empty folder.") }
                }
                items(browse.directories, key = { it.directory.path.toString() }) { overview ->
                    DirectoryRow(overview, onOpen = { stack = stack + overview.directory.path })
                }
                items(browse.files, key = { it.path.toString() }) { FileRow(it) }
            }
        }
    }
}

@Composable
private fun SummaryLine(summary: InventorySummary, modifier: Modifier) {
    val statuses = DirectoryStatus.entries
        .mapNotNull { status -> summary.directoriesByStatus[status]?.let { "${status.name.lowercase()}: $it" } }
        .joinToString()
    Text(
        "${summary.directoryCount} folders ($statuses) · ${summary.fileCount} files · ${formatBytes(summary.totalBytes)}",
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier,
    )
}

@Composable
private fun Breadcrumbs(stack: List<Path>, onNavigate: (depth: Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onNavigate(0) }) { Text("All roots") }
        stack.forEachIndexed { index, path ->
            Text("›")
            // Full path for the root, folder names below it
            TextButton(onClick = { onNavigate(index + 1) }) { Text(if (index == 0) path.toString() else path.name) }
        }
    }
}

@Composable
private fun DirectoryRow(overview: DirectoryOverview, onOpen: () -> Unit) {
    val dir = overview.directory
    ListRow(
        icon = "▸",
        name = if (dir.parentPath == null) dir.path.toString() else dir.path.name,
        details = "${dir.status.name.lowercase()} · ${dir.fileCount} files · ${formatBytes(overview.fileBytes)}" +
            " · scanned ${formatEpochMillis(dir.lastScanned)}" +
            if (dir.retryCount > 0) " · ${dir.retryCount} retries" else "",
        modifier = Modifier.clickable(onClick = onOpen),
    )
}

@Composable
private fun FileRow(file: FileRecord) {
    ListRow(
        icon = "·",
        name = file.name,
        details = "${formatBytes(file.sizeBytes)} · modified ${formatEpochMillis(file.modifiedAt)}",
    )
}

@Composable
private fun ListRow(icon: String, name: String, details: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(icon, style = MaterialTheme.typography.titleMedium)
        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(details, style = MaterialTheme.typography.bodySmall)
    }
}
