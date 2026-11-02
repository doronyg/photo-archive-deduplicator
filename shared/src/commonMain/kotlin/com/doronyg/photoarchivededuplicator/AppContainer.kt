package com.doronyg.photoarchivededuplicator

import app.cash.sqldelight.db.SqlDriver
import com.doronyg.photoarchivededuplicator.scan.DirectoryRepository
import com.doronyg.photoarchivededuplicator.scan.InventoryScanner
import com.doronyg.photoarchivededuplicator.scan.ScanController
import com.doronyg.photoarchivededuplicator.scan.createFilesDatabase
import com.doronyg.photoarchivededuplicator.retention.RetentionScoreService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the app's long-lived objects. Create exactly one per process (in the platform
 * entry point) and await [shutdown] on exit. Constructor injection keeps everything testable.
 *
 * [createScanner] picks the platform's scan strategy (file system walk on desktop, MediaStore on Android).
 */
class AppContainer internal constructor(
    private val driver: SqlDriver,
    private val databaseLane: IoLane,
    internal val filesystemLane: IoLane,
    workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    createScanner: (DirectoryRepository, IoLane) -> InventoryScanner,
    initialUiChoices: UiChoices = UiChoices(),
) {
    /** Lives as long as the app; scans run here, not in a composition scope. */
    private val appJob = SupervisorJob()
    private val appScope = CoroutineScope(appJob + workerDispatcher)
    private val shutdownLock = Mutex()
    private var closed = false

    val repository = DirectoryRepository(createFilesDatabase(driver), databaseLane = databaseLane)
    private val scanner: InventoryScanner = createScanner(repository, filesystemLane)
    val scanController = ScanController(scanner, appScope, repository)
    val uiPreferences = UiPreferences(initialUiChoices, appScope, repository::saveUiChoices)
    val retentionScores = RetentionScoreService(repository)

    /** Desktop awaits this after its UI exits, before the process can terminate. */
    suspend fun shutdown() = withContext(NonCancellable) {
        shutdownLock.withLock {
            if (closed) return@withLock
            scanController.pause()
            uiPreferences.shutdown()
            appJob.cancelAndJoin()
            try {
                // Native path actions are not children of appJob. Drain the filesystem
                // lane as well, including blocking calls whose callers were cancelled.
                filesystemLane.close {}
            } finally {
                databaseLane.close {
                    Diagnostics.logger.blockingOperation(LogTags.DATABASE, "driver.close") { driver.close() }
                }
            }
            closed = true
        }

    }
}

/** Own the resource even if cancellation wins at the IO-to-caller handoff. */
internal suspend fun createAppContainer(
    ioDispatcher: CoroutineDispatcher? = null,
    workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    openDriver: () -> SqlDriver,
    prepareFilesystem: () -> Unit = {},
    createScanner: (DirectoryRepository, IoLane) -> InventoryScanner,
): AppContainer {
    val databaseLane = ioDispatcher?.let { IoLane("database-test", it) } ?: IoLane.dedicated("inventory-db")
    val filesystemLane = ioDispatcher?.let { IoLane("filesystem-test", it) } ?: IoLane.dedicated("inventory-filesystem")
    var created: AppContainer? = null
    try {
        filesystemLane.run("startup directories", prepareFilesystem)
        return databaseLane.run("initialize driver/schema") {
            val driver = Diagnostics.logger.blockingOperation(LogTags.DATABASE, "open driver/schema/migrations") { openDriver() }
            try {
                val choices = loadUiChoicesInLane(createFilesDatabase(driver))
                AppContainer(driver, databaseLane, filesystemLane, workerDispatcher, createScanner, choices)
                    .also { created = it }
            } catch (e: Throwable) {
                Diagnostics.logger.blockingOperation(LogTags.DATABASE, "failed initialization driver.close") { driver.close() }
                throw e
            }
        }
    } catch (e: Throwable) {
        val container = created
        if (container != null) container.shutdown() else {
            filesystemLane.close {}
            databaseLane.close {}
        }
        throw e
    }
}
