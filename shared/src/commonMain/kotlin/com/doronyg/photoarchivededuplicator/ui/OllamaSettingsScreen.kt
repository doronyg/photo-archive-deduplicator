package com.doronyg.photoarchivededuplicator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.doronyg.photoarchivededuplicator.UiPreferences
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationController
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationException
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationRecord
import com.doronyg.photoarchivededuplicator.classification.OllamaSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun ollamaSettingsValidationError(settings: OllamaSettings): String? {
    if (settings.baseUrl.isBlank() && settings.model.isBlank()) return null
    return try {
        settings.context()
        null
    } catch (e: IllegalArgumentException) {
        e.message ?: "Enter a valid HTTP(S) server URL and model."
    }
}

internal const val DIRECTORY_CLASSIFICATION_PRIVACY =
    "Only when you click Classify, Refresh AI results, or Retry failed: directory paths/names " +
        "are sent to your configured Ollama server. No filenames, file contents, EXIF, scores, or groups are sent. " +
        "Results are advisory, not content verification. Fetch models requests installed model names without sending directory data. " +
        "Opening screens or saving settings never contacts the server."

@Composable
internal fun OllamaSettingsScreen(
    preferences: UiPreferences,
    controller: DirectoryClassificationController,
    loadModels: suspend (String) -> List<String>,
    loadResults: suspend () -> List<DirectoryClassificationRecord>,
    modifier: Modifier = Modifier,
) {
    val choices by preferences.state.collectAsState()
    val classificationState by controller.state.collectAsState()
    val savedContext = remember(choices.ollama) { runCatching { choices.ollama.context() }.getOrNull() }
    var draft by remember(choices.ollama) { mutableStateOf(choices.ollama) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var loadingModels by remember { mutableStateOf(false) }
    var models by remember(draft.baseUrl) { mutableStateOf<List<String>?>(null) }
    var modelsExpanded by remember(draft.baseUrl) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Ollama settings", style = MaterialTheme.typography.titleLarge)
        Text("Optional directory classification. No server or model is selected by default.")
        Text(DIRECTORY_CLASSIFICATION_PRIVACY, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(draft.baseUrl, { draft = draft.copy(baseUrl = it); status = null },
            label = { Text("Ollama HTTP(S) base URL") }, enabled = !saving && !loadingModels, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(draft.model, { draft = draft.copy(model = it); status = null },
            label = { Text("Model") }, enabled = !saving, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !saving && !loadingModels && draft.baseUrl.isNotBlank(), onClick = {
                error = null
                models = null
                loadingModels = true
                val server = draft.baseUrl
                scope.launch {
                    try {
                        models = loadModels(server)
                        modelsExpanded = models.orEmpty().isNotEmpty()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: IllegalArgumentException) {
                        error = e.message ?: "Enter a valid Ollama URL."
                    } catch (e: DirectoryClassificationException) {
                        error = "Could not fetch models: ${e.message}"
                    } finally {
                        loadingModels = false
                    }
                }
            }) { Text(if (loadingModels) "Fetching models..." else "Fetch models") }
            Box {
                OutlinedButton(enabled = !saving && !loadingModels && models.orEmpty().isNotEmpty(),
                    onClick = { modelsExpanded = true }) { Text("Choose model") }
                DropdownMenu(expanded = modelsExpanded, onDismissRequest = { modelsExpanded = false }) {
                    models.orEmpty().forEach { model ->
                        DropdownMenuItem(text = { Text(model) }, onClick = {
                            draft = draft.copy(model = model)
                            status = null
                            modelsExpanded = false
                        })
                    }
                }
            }
        }
        models?.let {
            Text(if (it.isEmpty()) "No installed models returned by this server."
                else "${it.size} installed models. Choose one or enter a model identifier manually.",
                style = MaterialTheme.typography.bodySmall)
        }
        Text("Saving changes cancels any active classification before updating the profile. Clear all fields to disable AI.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !saving && !loadingModels, onClick = {
                error = ollamaSettingsValidationError(draft)
                if (error == null) {
                    val saved = draft
                    saving = true
                    scope.launch {
                        try {
                            if (saved != preferences.state.value.ollama) controller.cancel()
                            preferences.update { it.copy(ollama = saved) }
                            status = "Settings saved. No AI request was made."
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = "Could not save settings: ${e.message}"
                        } finally {
                            saving = false
                        }
                    }
                }
            }) { Text(if (saving) "Saving..." else "Save settings") }
            OutlinedButton(enabled = !saving && !loadingModels, onClick = { draft = OllamaSettings(); error = null; status = null }) {
                Text("Clear configuration")
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        status?.let { Text(it) }
        DirectoryClassificationResults(loadResults, savedContext, classificationState.revision)
    }
}
