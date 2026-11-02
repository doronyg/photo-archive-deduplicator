package com.doronyg.photoarchivededuplicator.ui

import com.doronyg.photoarchivededuplicator.LogTags

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.doronyg.photoarchivededuplicator.AppContainer
import com.doronyg.photoarchivededuplicator.PathActions
import com.doronyg.photoarchivededuplicator.Diagnostics
import com.doronyg.photoarchivededuplicator.UiTab
import com.doronyg.photoarchivededuplicator.scan.ScanState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Loading is owned by the platform process, not cancelled when this composition disappears. */
@Composable
fun InventoryApp(loadContainer: suspend () -> AppContainer, scanTarget: ScanTarget, pathActions: PathActions) {
    var container by remember { mutableStateOf<AppContainer?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.Main) {
            try {
                container = loadContainer()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Diagnostics.logger.e(LogTags.CONTAINER, "Startup failed: ${e.message}")
                error = "Could not open inventory: ${e.message}"
            }
        }
    }
    val loaded = container
    if (loaded != null) {
        InventoryApp(loaded, scanTarget, pathActions)
    } else {
        MaterialTheme {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.safeDrawingPadding().padding(16.dp)) {
                    Text(error ?: "Loading inventory…")
                    LogsScreen(pathActions, Modifier.fillMaxSize())
                }
            }
        }
    }
}

/**
 * Inventory UI shared by desktop and Android; [scanTarget] decides how the scan root is chosen.
 */
@Composable
fun InventoryApp(container: AppContainer, scanTarget: ScanTarget, pathActions: PathActions) {
    val choices by container.uiPreferences.state.collectAsState()
    val preferencesError by container.uiPreferences.error.collectAsState()
    val selectedTab = choices.selectedTab
    val scanState by container.scanController.state.collectAsState()
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            // safeDrawingPadding keeps content clear of Android system bars; no-op on desktop
            Column(Modifier.safeDrawingPadding()) {
                preferencesError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp))
                }
                PrimaryTabRow(selectedTabIndex = selectedTab.ordinal) {
                    UiTab.entries.forEach { tab ->
                        Tab(
                            selected = selectedTab == tab,
                            onClick = { container.uiPreferences.update { it.copy(selectedTab = tab) } },
                            text = { Text(tab.title) },
                        )
                    }
                }
                val content = Modifier.fillMaxSize().padding(16.dp)
                when (selectedTab) {
                    UiTab.SCAN -> ScanScreen(container.scanController, scanTarget, container.uiPreferences, content)
                    UiTab.BROWSE -> BrowseScreen(container.repository, pathActions, container.uiPreferences, content, scanState is ScanState.Running)
                    UiTab.DUPLICATES -> DuplicatesScreen(container.repository, scanTarget, pathActions, container.uiPreferences, content)
                    UiTab.EXCLUSIONS -> ExclusionsScreen(container.repository, scanState is ScanState.Running, content)
                    UiTab.LOGS -> LogsScreen(pathActions, content, container.uiPreferences)
                }
            }
        }
    }
}
