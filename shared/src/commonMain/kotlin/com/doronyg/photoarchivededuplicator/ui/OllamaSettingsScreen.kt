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
import com.doronyg.photoarchivededuplicator.strings.AppStrings
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
        e.message ?: AppStrings.analysis.invalidSettings
    }
}

internal val DIRECTORY_CLASSIFICATION_PRIVACY = AppStrings.analysis.privacy

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
        Text(AppStrings.analysis.settingsTitle, style = MaterialTheme.typography.titleLarge)
        Text(AppStrings.analysis.settingsDescription)
        Text(DIRECTORY_CLASSIFICATION_PRIVACY, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(draft.baseUrl, { draft = draft.copy(baseUrl = it); status = null },
            label = { Text(AppStrings.analysis.serverUrl) }, enabled = !saving && !loadingModels, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(draft.model, { draft = draft.copy(model = it); status = null },
            label = { Text(AppStrings.analysis.model) }, enabled = !saving, singleLine = true, modifier = Modifier.fillMaxWidth())
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
                        error = e.message ?: AppStrings.analysis.invalidUrl
                    } catch (e: DirectoryClassificationException) {
                        error = AppStrings.analysis.fetchModelsError(e.message)
                    } finally {
                        loadingModels = false
                    }
                }
            }) { Text(if (loadingModels) AppStrings.analysis.fetchingModels else AppStrings.analysis.fetchModels) }
            Box {
                OutlinedButton(enabled = !saving && !loadingModels && models.orEmpty().isNotEmpty(),
                    onClick = { modelsExpanded = true }) { Text(AppStrings.analysis.chooseModel) }
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
            Text(if (it.isEmpty()) AppStrings.analysis.noModels
                else AppStrings.analysis.modelCount(it.size),
                style = MaterialTheme.typography.bodySmall)
        }
        Text(AppStrings.analysis.disableHelp)
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
                            status = AppStrings.analysis.settingsSaved
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = AppStrings.analysis.saveSettingsError(e.message)
                        } finally {
                            saving = false
                        }
                    }
                }
            }) { Text(if (saving) AppStrings.analysis.saving else AppStrings.analysis.saveSettings) }
            OutlinedButton(enabled = !saving && !loadingModels, onClick = { draft = OllamaSettings(); error = null; status = null }) {
                Text(AppStrings.analysis.clearConfiguration)
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        status?.let { Text(it) }
        DirectoryClassificationResults(loadResults, savedContext, classificationState.revision)
    }
}
