package com.doronyg.photoarchivededuplicator

import app.cash.sqldelight.db.SqlDriver
import com.doronyg.photoarchivededuplicator.scan.DirectoryRepository
import com.doronyg.photoarchivededuplicator.scan.InventoryScanner
import com.doronyg.photoarchivededuplicator.scan.ScanController
import com.doronyg.photoarchivededuplicator.scan.createFilesDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Owns the app's long-lived objects. Create exactly one per process (in the platform
 * entry point) and [close] it on exit. Constructor injection keeps everything testable.
 *
 * [createScanner] picks the platform's scan strategy (file system walk on desktop, MediaStore on Android).
 */
class AppContainer(
    private val driver: SqlDriver,
    createScanner: (DirectoryRepository) -> InventoryScanner,
) : AutoCloseable {
    /** Lives as long as the app; scans run here, not in a composition scope. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val repository = DirectoryRepository(createFilesDatabase(driver), Dispatchers.IO)
    val scanner: InventoryScanner = createScanner(repository)
    val scanController = ScanController(scanner, appScope)

    override fun close() {
        appScope.cancel()
        driver.close()
    }
}
