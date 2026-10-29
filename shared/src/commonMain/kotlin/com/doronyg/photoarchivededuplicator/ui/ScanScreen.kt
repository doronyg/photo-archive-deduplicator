package com.doronyg.photoarchivededuplicator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.doronyg.photoarchivededuplicator.scan.ScanController
import com.doronyg.photoarchivededuplicator.scan.ScanProgress
import com.doronyg.photoarchivededuplicator.scan.ScanState
import okio.Path
import okio.Path.Companion.toPath

/** How the user picks what to scan. */
sealed interface ScanTarget {
    /** Any folder: typed, or chosen with [pickDirectory] (returns null if cancelled). */
    class ChosenFolder(val pickDirectory: () -> String?) : ScanTarget

    /**
     * Always [root]; e.g. Android's media library.
     * [requestAccess] obtains any needed permission and calls its argument only once access is granted.
     */
    class Fixed(
        val root: Path,
        val buttonLabel: String,
        val requestAccess: (onGranted: () -> Unit) -> Unit = { it() },
    ) : ScanTarget
}

@Composable
fun ScanScreen(controller: ScanController, target: ScanTarget, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()
    val running = state is ScanState.Running

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (target) {
            is ScanTarget.ChosenFolder -> ChosenFolderControls(controller, target, running)
            is ScanTarget.Fixed -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { target.requestAccess { controller.start(target.root) } }, enabled = !running) {
                    Text(target.buttonLabel)
                }
                OutlinedButton(onClick = controller::pause, enabled = running) { Text("Pause") }
            }
        }
        ScanStatus(state)
    }
}

@Composable
private fun ChosenFolderControls(controller: ScanController, target: ScanTarget.ChosenFolder, running: Boolean) {
    var rootText by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = rootText,
                onValueChange = { rootText = it },
                label = { Text("Folder to scan") },
                singleLine = true,
                enabled = !running,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { target.pickDirectory()?.let { rootText = it } }, enabled = !running) {
                Text("Choose…")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { controller.start(rootText.trim().toPath()) }, enabled = !running && rootText.isNotBlank()) {
                Text("Start scan")
            }
            OutlinedButton(onClick = controller::pause, enabled = running) { Text("Pause") }
            // Also continues a scan interrupted by quitting the app
            OutlinedButton(onClick = { controller.resume() }, enabled = !running) { Text("Resume") }
        }
    }
}

@Composable
private fun ScanStatus(state: ScanState) {
    val (label, progress) = when (state) {
        ScanState.Idle -> "Idle" to null
        is ScanState.Running -> "Scanning…" to state.progress
        is ScanState.Paused -> "Paused" to state.progress
        is ScanState.Completed -> "Completed" to state.progress
        is ScanState.Failed -> "Failed: ${state.message}" to state.progress
    }
    Text(label, style = MaterialTheme.typography.titleMedium)
    progress?.let { ProgressDetails(it) }
}

@Composable
private fun ProgressDetails(progress: ScanProgress) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Text("Directories scanned: ${progress.scannedDirectories}")
        Text("Directories pending: ${progress.pendingDirectories}")
        Text("Directories failed: ${progress.failedDirectories}")
        Text("Files found: ${progress.filesFound}")
        Text("Elapsed: ${formatElapsed(progress.elapsedMillis)}")
        progress.currentDirectory?.let {
            Text("Current: $it", style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
    }
}
