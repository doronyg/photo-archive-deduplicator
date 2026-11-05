package com.doronyg.photoarchivededuplicator.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.doronyg.photoarchivededuplicator.PathActions
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import com.doronyg.photoarchivededuplicator.strings.AppStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun PathLink(label: String, action: suspend () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var error by remember(label) { mutableStateOf<String?>(null) }
    var busy by remember(label) { mutableStateOf(false) }
    Column(modifier) {
        Text(label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable(enabled = !busy, role = Role.Button) {
                scope.launch {
                    busy = true
                    error = null
                    try {
                        action()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.message ?: AppStrings.inventory.openPathFailed
                    } finally {
                        busy = false
                    }
                }
            }.padding(vertical = 4.dp),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

/** Separate folder and filename targets; neither navigates the inventory internally. */
@Composable
internal fun FilePathLinks(file: FileRecord, actions: PathActions, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        PathLink(file.name, action = { actions.openFile(file.path) })
        PathLink(AppStrings.inventory.folderPath(file.directoryPath.toString()), action = { actions.openFolder(file.directoryPath) })
    }
}
