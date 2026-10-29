package com.doronyg.photoarchivededuplicator.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.doronyg.photoarchivededuplicator.AppContainer

private enum class InventoryTab(val title: String) { SCAN("Scan"), BROWSE("Browse") }

/**
 * Inventory UI shared by desktop and Android; [scanTarget] decides how the scan root is chosen.
 */
@Composable
fun InventoryApp(container: AppContainer, scanTarget: ScanTarget) {
    var selectedTab by rememberSaveable { mutableIntStateOf(InventoryTab.SCAN.ordinal) }
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            // safeDrawingPadding keeps content clear of Android system bars; no-op on desktop
            Column(Modifier.safeDrawingPadding()) {
                PrimaryTabRow(selectedTabIndex = selectedTab) {
                    InventoryTab.entries.forEach { tab ->
                        Tab(
                            selected = selectedTab == tab.ordinal,
                            onClick = { selectedTab = tab.ordinal },
                            text = { Text(tab.title) },
                        )
                    }
                }
                val content = Modifier.fillMaxSize().padding(16.dp)
                when (InventoryTab.entries[selectedTab]) {
                    InventoryTab.SCAN -> ScanScreen(container.scanController, scanTarget, content)
                    InventoryTab.BROWSE -> BrowseScreen(container.repository, content)
                }
            }
        }
    }
}
