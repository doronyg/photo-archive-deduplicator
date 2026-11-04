package com.doronyg.photoarchivededuplicator

import app.cash.sqldelight.db.SqlDriver
import com.doronyg.photoarchivededuplicator.scan.DirectoryRepository
import com.doronyg.photoarchivededuplicator.scan.InventoryScanner
import com.doronyg.photoarchivededuplicator.scan.ScanController
import com.doronyg.photoarchivededuplicator.scan.createFilesDatabase
import com.doronyg.photoarchivededuplicator.retention.RetentionScoreService
import com.doronyg.photoarchivededuplicator.exif.ExifExtractionService
import com.doronyg.photoarchivededuplicator.exif.ExifQueueController
import com.doronyg.photoarchivededuplicator.exif.ExifSource
import com.doronyg.photoarchivededuplicator.exif.FileSystemExifSource
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationController
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassifier
import com.doronyg.photoarchivededuplicator.classification.OllamaDirectoryClassifier
import com.doronyg.photoarchivededuplicator.classification.createOllamaHttpClient
import com.doronyg.photoarchivededuplicator.classification.fetchOllamaModels
import com.doronyg.photoarchivededuplicator.classification.ollamaBaseUrl
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.FileSystem

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
    createExifSource: (IoLane) -> ExifSource = { FileSystemExifSource(FileSystem.SYSTEM, it) },
    createOllamaClient: () -> HttpClient = ::createOllamaHttpClient,
) {
    /** Lives as long as the app; scans run here, not in a composition scope. */
    private val appJob = SupervisorJob()
    private val appScope = CoroutineScope(appJob + workerDispatcher)
    private val shutdownLock = Mutex()
    private var closed = false

    val repository = DirectoryRepository(createFilesDatabase(driver), databaseLane = databaseLane)
    private val ollamaClient = lazy(createOllamaClient)
    val directoryClassifications = DirectoryClassificationController(appScope,
        DirectoryClassifier { directory, context -> OllamaDirectoryClassifier(ollamaClient.value).classify(directory, context) },
        load = repository::directoryClassifications, save = repository::saveDirectoryClassification,
        dispatcher = workerDispatcher)
    val exif = ExifExtractionService(repository, createExifSource(filesystemLane))
    val retentionScores = RetentionScoreService(repository)
    val exifQueue = ExifQueueController(repository, retentionScores, exif, appScope, dispatcher = workerDispatcher)
    private val scanner: InventoryScanner = createScanner(repository, filesystemLane)
    val scanController = ScanController(scanner, appScope, repository,
        onScanAccepted = { exifQueue.scanAccepted() },
        beforeScan = { exifQueue.scanStarting() },
        afterScan = { root, completed ->
            if (completed && root != null) exifQueue.scanCompleted(root)
            else exifQueue.scanStopped()
        })
    val uiPreferences = UiPreferences(initialUiChoices, appScope, repository::saveUiChoices)

    internal suspend fun availableOllamaModels(baseUrl: String): List<String> {
        val server = ollamaBaseUrl(baseUrl)
        check(!closed && appJob.isActive) { "Application is closed" }
        val request = appScope.async { fetchOllamaModels(ollamaClient.value, server) }
        try {
            return request.await()
        } finally {
            request.cancel()
        }
    }

    /** Desktop awaits this after its UI exits, before the process can terminate. */
    suspend fun shutdown() = withContext(NonCancellable) {
        shutdownLock.withLock {
            if (closed) return@withLock
            directoryClassifications.shutdown()
            exifQueue.shutdown()
            scanController.pause()
            uiPreferences.shutdown()
            appJob.cancelAndJoin()
            try {
                if (ollamaClient.isInitialized()) {
                    ollamaClient.value.close()
                    ollamaClient.value.coroutineContext[Job]?.join()
                }
            } finally {
                try {
                    // Native path actions are not children of appJob. Drain the filesystem
                    // lane as well, including blocking calls whose callers were cancelled.
                    filesystemLane.close {}
                } finally {
                    databaseLane.close {
                        Diagnostics.logger.blockingOperation(LogTags.DATABASE, "driver.close") { driver.close() }
                    }
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
    createExifSource: (IoLane) -> ExifSource = { FileSystemExifSource(FileSystem.SYSTEM, it) },
    createOllamaClient: () -> HttpClient = ::createOllamaHttpClient,
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
                AppContainer(driver, databaseLane, filesystemLane, workerDispatcher, createScanner, choices, createExifSource,
                    createOllamaClient)
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
