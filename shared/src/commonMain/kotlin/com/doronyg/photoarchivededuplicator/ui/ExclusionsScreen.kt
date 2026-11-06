package com.doronyg.photoarchivededuplicator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import com.doronyg.photoarchivededuplicator.scan.DirectoryExclusion
import com.doronyg.photoarchivededuplicator.scan.DirectoryRepository
import com.doronyg.photoarchivededuplicator.strings.AppStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import okio.Path.Companion.toPath

@Composable
fun ExclusionsScreen(repository: DirectoryRepository, scanning: Boolean, modifier: Modifier = Modifier) {
    var path by rememberSaveable { mutableStateOf("") }
    var reason by rememberSaveable { mutableStateOf("") }
    var exclusions by remember { mutableStateOf(emptyList<DirectoryExclusion>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() { exclusions = repository.exclusions() }
    fun edit(action: suspend () -> Unit) {
        scope.launch {
            busy = true
            error = null
            try {
                action()
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = AppStrings.inventory.updateExclusionsFailed(e.message)
            } finally {
                busy = false
            }
        }
    }
    LaunchedEffect(repository) {
        try {
            refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = AppStrings.inventory.loadExclusionsFailed(e.message)
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(AppStrings.inventory.exclusionsTitle, style = MaterialTheme.typography.titleLarge)
        Text(AppStrings.inventory.exclusionsHelp)
        if (scanning) Text(AppStrings.inventory.pauseToEditExclusions)
        OutlinedTextField(
            value = path, onValueChange = { path = it }, label = { Text(AppStrings.inventory.exclusionPath) },
            singleLine = true, enabled = !scanning && !busy, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = reason, onValueChange = { reason = it }, label = { Text(AppStrings.inventory.exclusionReason) },
            enabled = !scanning && !busy, modifier = Modifier.fillMaxWidth(),
        )
        Button(enabled = !scanning && !busy && path.isNotBlank(), onClick = {
            val newPath = path.trim()
            val newReason = reason
            edit {
                repository.addExclusion(newPath.toPath(), newReason)
                path = ""
                reason = ""
            }
        }) { Text(AppStrings.inventory.addOrUpdateExclusion) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (exclusions.isEmpty()) item { Text(AppStrings.inventory.noExclusions) }
            items(exclusions, key = { it.path.toString() }) { exclusion ->
                Column {
                    Text(exclusion.path.toString())
                    exclusion.reason?.let { Text(it) }
                    if (!exclusion.isSystem) {
                        TextButton(enabled = !scanning && !busy, onClick = {
                            edit { repository.removeExclusion(exclusion.path) }
                        }) { Text(AppStrings.inventory.removeExclusion(exclusion.path.toString())) }
                    }
                }
            }
        }
    }
}
