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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.doronyg.photoarchivededuplicator.scan.ScanController
import com.doronyg.photoarchivededuplicator.scan.ScanProgress
import com.doronyg.photoarchivededuplicator.scan.ScanState
import com.doronyg.photoarchivededuplicator.UiPreferences
import com.doronyg.photoarchivededuplicator.strings.AppStrings
import okio.Path
import okio.Path.Companion.toPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** How the user picks what to scan. */
sealed interface ScanTarget {
    /** Any folder: typed, or chosen with [pickDirectory] (returns null if cancelled). */
    class ChosenFolder(val pickDirectory: suspend () -> String?) : ScanTarget

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
fun ScanScreen(controller: ScanController, target: ScanTarget, preferences: UiPreferences, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()
    val running = state is ScanState.Running
    val scope = rememberCoroutineScope()

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (target) {
            is ScanTarget.ChosenFolder -> ChosenFolderControls(controller, target, running, preferences)
            is ScanTarget.Fixed -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch(Dispatchers.Main) {
                        target.requestAccess { scope.launch(Dispatchers.Main) { controller.start(target.root) } }
                    }
                }, enabled = !running) {
                    Text(target.buttonLabel)
                }
                OutlinedButton(onClick = controller::pause, enabled = running) { Text(AppStrings.inventory.pause) }
                OutlinedButton(onClick = {
                    scope.launch(Dispatchers.Main) {
                        target.requestAccess { scope.launch(Dispatchers.Main) { controller.resume() } }
                    }
                }, enabled = !running) { Text(AppStrings.inventory.resume) }
            }
        }
        ScanStatus(state)
    }
}

@Composable
private fun ChosenFolderControls(controller: ScanController, target: ScanTarget.ChosenFolder, running: Boolean, preferences: UiPreferences) {
    val choices by preferences.state.collectAsState()
    val rootText = choices.scanRoot
    var pickerError by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = rootText,
                onValueChange = { value -> preferences.update { it.copy(scanRoot = value) } },
                label = { Text(AppStrings.inventory.folderToScan) },
                singleLine = true,
                enabled = !running,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = {
                scope.launch(Dispatchers.Main) {
                    pickerError = null
                    try {
                        target.pickDirectory()?.let { path -> preferences.update { it.copy(scanRoot = path) } }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        pickerError = AppStrings.inventory.chooseFolderFailed(e.message)
                    }
                }
            }, enabled = !running) {
                Text(AppStrings.inventory.chooseFolder)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { scope.launch { controller.start(rootText.trim().toPath()) } }, enabled = !running && rootText.isNotBlank()) {
                Text(AppStrings.inventory.startScan)
            }
            OutlinedButton(onClick = controller::pause, enabled = running) { Text(AppStrings.inventory.pause) }
            // Also continues a scan interrupted by quitting the app
            OutlinedButton(onClick = {
                scope.launch { controller.resume(rootText.trim().takeIf { it.isNotEmpty() }?.toPath()) }
            }, enabled = !running) { Text(AppStrings.inventory.resume) }
        }
        pickerError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text(AppStrings.inventory.scanControlsHelp,
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ScanStatus(state: ScanState) {
    val (label, progress) = when (state) {
        ScanState.Idle -> AppStrings.inventory.idle to null
        is ScanState.Running -> AppStrings.inventory.scanning to state.progress
        is ScanState.Paused -> AppStrings.inventory.paused to state.progress
        is ScanState.Completed -> AppStrings.inventory.completed to state.progress
        is ScanState.Failed -> AppStrings.inventory.scanFailed(state.message) to state.progress
    }
    Text(label, style = MaterialTheme.typography.titleMedium)
    progress?.let { ProgressDetails(it) }
}

@Composable
private fun ProgressDetails(progress: ScanProgress) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Text(AppStrings.inventory.scannedDirectories(progress.scannedDirectories))
        Text(AppStrings.inventory.pendingDirectories(progress.pendingDirectories))
        Text(AppStrings.inventory.failedDirectories(progress.failedDirectories))
        Text(AppStrings.inventory.filesFound(progress.filesFound))
        progress.root?.let { Text(AppStrings.inventory.scanRoot(it.toString())) }
        Text(AppStrings.inventory.currentRun(formatElapsed(progress.elapsedMillis)))
        Text(AppStrings.inventory.totalActive(formatElapsed(progress.totalElapsedMillis)))
        progress.currentDirectory?.let {
            Text(AppStrings.inventory.currentDirectory(it.toString()), style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
    }
}
