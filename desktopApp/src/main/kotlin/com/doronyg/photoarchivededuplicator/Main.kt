package com.doronyg.photoarchivededuplicator

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.doronyg.photoarchivededuplicator.ui.InventoryApp
import com.doronyg.photoarchivededuplicator.ui.ScanTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel

fun main() {
    val logShutdown = Thread({
        runBlocking { Diagnostics.logger.shutdown() }
    }, "Diagnostics-shutdown")
    Runtime.getRuntime().addShutdownHook(logShutdown)
    Diagnostics.logger.i(LogTags.LIFECYCLE, "Desktop starting")
    val lifetime = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val container = lifetime.async { createDesktopContainer() }
    val pathActions = desktopPathActions { container.await() }
    try {
        application {
            Window(onCloseRequest = ::exitApplication, title = APP_NAME) {
                InventoryApp(loadContainer = { container.await() },
                    ScanTarget.ChosenFolder(pickDirectory = { pickDirectory(window, container.await()) }), pathActions)
            }
        }
    } finally {
        // application() has returned: this is the process thread, not Swing's UI thread.
        // Await initialization even if the user closed the window while it was loading.
        try {
            runBlocking(Dispatchers.IO) { container.await().shutdown() }
        } finally {
            lifetime.cancel()
            Diagnostics.logger.i(LogTags.LIFECYCLE, "Desktop stopping")
            runBlocking { Diagnostics.logger.shutdown() }
            // SIGTERM may already be running this hook concurrently.
            runCatching { Runtime.getRuntime().removeShutdownHook(logShutdown) }
        }
    }
}
