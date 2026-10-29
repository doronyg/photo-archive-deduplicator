package com.doronyg.photoarchivededuplicator

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.doronyg.photoarchivededuplicator.ui.InventoryApp
import com.doronyg.photoarchivededuplicator.ui.ScanTarget

fun main() {
    val container = createDesktopContainer()
    try {
        application {
            Window(onCloseRequest = ::exitApplication, title = APP_NAME) {
                InventoryApp(container, ScanTarget.ChosenFolder(pickDirectory = { pickDirectory(window) }))
            }
        }
    } finally {
        container.close()
    }
}
