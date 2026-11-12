package com.doronyg.photoarchivededuplicator.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.doronyg.photoarchivededuplicator.PathActions
import com.doronyg.photoarchivededuplicator.UiPreferences
import com.doronyg.photoarchivededuplicator.scan.DirectoryOverview
import com.doronyg.photoarchivededuplicator.scan.DirectoryRepository
import com.doronyg.photoarchivededuplicator.scan.DirectoryStatus
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import com.doronyg.photoarchivededuplicator.scan.InventorySummary
import com.doronyg.photoarchivededuplicator.strings.AppStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import okio.Path
import okio.Path.Companion.toPath

private data class BrowseContent(
    val summary: InventorySummary,
    val directories: List<DirectoryOverview>,
    val files: List<FileRecord>,
)

/** Read-only view of the inventory: drill down from the scan roots. */
@Composable
fun BrowseScreen(repository: DirectoryRepository, pathActions: PathActions, preferences: UiPreferences, modifier: Modifier = Modifier, scanning: Boolean = false) {
    // Empty stack = list of scan roots
    val choices by preferences.state.collectAsState()
    val stack = remember(choices.browseStack) { choices.browseStack.map { it.toPath() } }
    var refreshCount by remember { mutableIntStateOf(0) }
    var content by remember { mutableStateOf<BrowseContent?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var excluded by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val current = stack.lastOrNull()
    val clipboard = LocalClipboardManager.current
    var copying by remember { mutableStateOf(false) }
    var copyStatus by remember(current) { mutableStateOf<String?>(null) }

    LaunchedEffect(current, refreshCount) {
        content = null
        error = null
        try {
            excluded = current?.let { repository.isExcluded(it) } ?: false
            content = BrowseContent(
                summary = repository.summary(),
                directories = repository.childOverviews(current),
                files = current?.let { repository.filesIn(it) }.orEmpty(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = AppStrings.inventory.loadInventoryFailed(e.message)
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            content?.summary?.let { SummaryLine(it, Modifier.weight(1f)) }
            OutlinedButton(onClick = { refreshCount++ }) { Text(AppStrings.refresh) }
        }
        BrowseBreadcrumbs(stack, onNavigate = { depth -> preferences.update { it.copy(browseStack = it.browseStack.take(depth)) } })
        OutlinedButton(enabled = !copying && content != null, onClick = {
            val selected = current
            copying = true
            copyStatus = null
            error = null
            scope.launch {
                try {
                    val paths = repository.filePathsUnder(selected)
                    val text = formatInventoryFilePaths(paths)
                    clipboard.setText(AnnotatedString(text))
                    copyStatus = AppStrings.inventory.copiedFilePaths(paths.size, selected?.toString())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = AppStrings.inventory.copyFilePathsFailed(selected?.toString(), e.message)
                } finally {
                    copying = false
                }
            }
        }) { Text(if (copying) AppStrings.inventory.copying else AppStrings.inventory.copyFilePaths) }
        Text(AppStrings.inventory.copyFilePathsHelp,
            style = MaterialTheme.typography.bodySmall)
        copyStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        current?.let { folder ->
            PathLink(AppStrings.inventory.openFolderInFileManager(folder.toString()), action = { pathActions.openFolder(folder) })
            if (excluded) {
                Text(AppStrings.inventory.excludedTreeNotice)
            } else {
                OutlinedButton(enabled = !scanning && !editing, onClick = {
                    scope.launch {
                        editing = true
                        error = null
                        try {
                            repository.addExclusion(folder)
                            refreshCount++
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = AppStrings.inventory.excludeFolderFailed(e.message)
                        } finally {
                            editing = false
                        }
                    }
                }) { Text(AppStrings.inventory.excludeFolderTree) }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        HorizontalDivider()
        content?.let { browse ->
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                if (browse.directories.isEmpty() && browse.files.isEmpty()) {
                    item { Text(if (current == null) AppStrings.inventory.nothingScanned else AppStrings.inventory.emptyFolder) }
                }
                items(browse.directories, key = { it.directory.path.toString() }) { overview ->
                    Column {
                        DirectoryRow(overview, onOpen = {
                            preferences.update { it.copy(browseStack = it.browseStack + overview.directory.path.toString()) }
                        })
                        PathLink(AppStrings.inventory.openInFileManager, action = { pathActions.openFolder(overview.directory.path) })
                    }
                }
                items(browse.files, key = { it.path.toString() }) { FileRow(it, pathActions) }
            }
        }
    }
}

@Composable
private fun SummaryLine(summary: InventorySummary, modifier: Modifier) {
    val statuses = DirectoryStatus.entries
        .mapNotNull { status -> summary.directoriesByStatus[status]?.let { AppStrings.inventory.directoryStatusCount(status.name.lowercase(), it) } }
        .joinToString()
    Text(
        AppStrings.inventory.summary(summary.directoryCount, statuses, summary.fileCount, formatBytes(summary.totalBytes)),
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier,
    )
}

@Composable
internal fun BrowseBreadcrumbs(stack: List<Path>, onNavigate: (depth: Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onNavigate(0) }) { Text(AppStrings.inventory.allRoots) }
        stack.forEachIndexed { index, path ->
            Text(AppStrings.inventory.breadcrumbSeparator)
            // Full path for the root, folder names below it
            TextButton(onClick = { onNavigate(index + 1) }) { Text(if (index == 0) path.toString() else path.name) }
        }
    }
}

@Composable
private fun DirectoryRow(overview: DirectoryOverview, onOpen: () -> Unit) {
    val dir = overview.directory
    BrowseDirectoryListRow(
        icon = AppStrings.inventory.directoryIcon,
        name = if (dir.parentPath == null) dir.path.toString() else dir.path.name,
        details = AppStrings.inventory.directoryDetails(
            dir.status.name.lowercase(), dir.fileCount, formatBytes(overview.fileBytes),
            formatEpochMillis(dir.lastScanned), dir.retryCount,
        ),
        modifier = Modifier.clickable(onClick = onOpen),
    )
}

@Composable
private fun FileRow(file: FileRecord, actions: PathActions) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        FilePathLinks(file, actions)
        Text(AppStrings.inventory.fileDetails(formatBytes(file.sizeBytes), formatEpochMillis(file.createdAt), formatEpochMillis(file.modifiedAt)),
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun BrowseDirectoryListRow(icon: String, name: String, details: String, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        val compact = maxWidth < 600.dp
        val maxDetailsWidth = maxWidth / 2
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(icon, style = MaterialTheme.typography.titleMedium)
            if (compact) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(details, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(details, modifier = Modifier.widthIn(max = maxDetailsWidth),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
