package com.doronyg.photoarchivededuplicator.scan

import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.db.SqlDriver
import com.doronyg.photoarchivededuplicator.db.FilesDatabase
import com.doronyg.photoarchivededuplicator.db.ScanDirectory
import com.doronyg.photoarchivededuplicator.db.ScanFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Path
import okio.Path.Companion.toPath

fun createFilesDatabase(driver: SqlDriver): FilesDatabase =
    FilesDatabase(
        driver = driver,
        scanDirectoryAdapter = ScanDirectory.Adapter(statusAdapter = EnumColumnAdapter()),
    )

data class DirectoryRecord(
    val path: Path,
    val parentPath: Path?,
    val status: DirectoryStatus,
    val retryCount: Int,
    val fileCount: Int,
    val modifiedAt: Long?,
    val lastScanned: Long?,
)

data class FileRecord(
    val path: Path,
    val directoryPath: Path,
    val name: String,
    val extension: String?,
    val sizeBytes: Long,
    val modifiedAt: Long?,
    val lastScanned: Long,
)

/** Result of listing a single directory; children only, not recursive. */
data class DirectoryListing(
    val subdirectories: List<Entry>,
    val files: List<Entry>,
) {
    data class Entry(val path: Path, val sizeBytes: Long, val modifiedAt: Long?)
}

/**
 * Persists scan progress. Paths are stored as Okio path strings.
 *
 * Every operation is a suspend function that moves itself onto [ioDispatcher],
 * so callers never block their own thread on SQLite, whatever dispatcher they use.
 * Inject a test dispatcher in tests only.
 */
class DirectoryRepository(
    private val database: FilesDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val directories = database.scanDirectoryQueries
    private val files = database.scanFileQueries

    /** Queues [root] for scanning, re-queuing it if a previous scan already processed it. */
    suspend fun seedRoot(root: Path) = io {
        database.transaction { enqueue(root, parent = null, modifiedAt = null) }
    }

    /** Returns directories left IN_PROGRESS by an interrupted run to the queue. */
    suspend fun resetInterrupted() = io {
        directories.replaceStatus(newStatus = DirectoryStatus.PENDING, oldStatus = DirectoryStatus.IN_PROGRESS)
    }

    suspend fun fetchPendingBatch(limit: Int): List<Path> = io {
        directories.selectBatchByStatus(DirectoryStatus.PENDING, limit.toLong()).awaitAsList().map { it.toPath() }
    }

    suspend fun markInProgress(path: Path) = io {
        directories.updateStatus(DirectoryStatus.IN_PROGRESS, path.toString())
    }

    /**
     * Atomically reconciles stored children of [dir] with a fresh [listing]
     * and marks [dir] COMPLETED.
     */
    suspend fun recordListing(dir: Path, listing: DirectoryListing, scannedAt: Long) = io {
        database.transaction {
            val dirKey = dir.toString()

            val listedDirs = listing.subdirectories.associateBy { it.path.toString() }
            for (stale in directories.selectChildPaths(dirKey).awaitAsList() - listedDirs.keys) {
                deleteSubtree(stale)
            }
            for (sub in listing.subdirectories) {
                enqueue(sub.path, parent = dir, modifiedAt = sub.modifiedAt)
            }

            val listedFiles = listing.files.associateBy { it.path.toString() }
            for (stale in files.selectPathsInDirectory(dirKey).awaitAsList() - listedFiles.keys) {
                files.deleteByPath(stale)
            }
            for ((key, file) in listedFiles) {
                files.updateFile(file.sizeBytes, file.modifiedAt, scannedAt, key)
                files.insertFileIfAbsent(
                    path = key,
                    directoryPath = dirKey,
                    name = file.path.name,
                    extension = extensionOf(file.path.name),
                    sizeBytes = file.sizeBytes,
                    modifiedAt = file.modifiedAt,
                    lastScanned = scannedAt,
                )
            }

            directories.recordSuccess(DirectoryStatus.COMPLETED, listing.files.size.toLong(), scannedAt, dirKey)
        }
    }

    /**
     * Records a failed listing attempt and returns the updated record: PENDING while
     * attempts remain, FAILED once [maxAttempts] is reached. Stored children are kept.
     */
    suspend fun recordFailure(path: Path, maxAttempts: Int): DirectoryRecord = io {
        database.transactionWithResult {
            val key = path.toString()
            val attempts = (directories.selectByPath(key).awaitAsOneOrNull()?.retryCount ?: 0) + 1
            val status = if (attempts >= maxAttempts) DirectoryStatus.FAILED else DirectoryStatus.PENDING
            directories.recordFailure(status, attempts, key)
            directories.selectByPath(key).awaitAsOneOrNull()!!.toRecord()
        }
    }

    suspend fun directory(path: Path): DirectoryRecord? = io {
        directories.selectByPath(path.toString()).awaitAsOneOrNull()?.toRecord()
    }

    suspend fun allDirectories(): List<DirectoryRecord> = io {
        directories.selectAll().awaitAsList().map { it.toRecord() }
    }

    suspend fun file(path: Path): FileRecord? = io {
        files.selectByPath(path.toString()).awaitAsOneOrNull()?.toRecord()
    }

    suspend fun allFiles(): List<FileRecord> = io {
        files.selectAll().awaitAsList().map { it.toRecord() }
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(ioDispatcher) { block() }

    // Private helpers run inside an io { } transaction; they must not switch context themselves.
    private suspend fun enqueue(path: Path, parent: Path?, modifiedAt: Long?) {
        val key = path.toString()
        val parentKey = parent?.toString()
        directories.insertIfAbsent(key, parentKey, DirectoryStatus.PENDING, modifiedAt)
        directories.requeue(DirectoryStatus.PENDING, parentKey, modifiedAt, key)
    }

    private suspend fun deleteSubtree(rootKey: String) {
        for (key in directories.selectSubtree(rootKey).awaitAsList()) {
            files.deleteInDirectory(key)
            directories.deleteByPath(key)
        }
    }

    private fun ScanDirectory.toRecord() = DirectoryRecord(
        path = path.toPath(),
        parentPath = parentPath?.toPath(),
        status = status,
        retryCount = retryCount.toInt(),
        fileCount = fileCount.toInt(),
        modifiedAt = modifiedAt,
        lastScanned = lastScanned,
    )

    private fun ScanFile.toRecord() = FileRecord(
        path = path.toPath(),
        directoryPath = directoryPath.toPath(),
        name = name,
        extension = extension,
        sizeBytes = sizeBytes,
        modifiedAt = modifiedAt,
        lastScanned = lastScanned,
    )
}

/** Lower-cased text after the last dot; null for no extension or dot-files like ".hidden". */
internal fun extensionOf(fileName: String): String? {
    val dot = fileName.lastIndexOf('.')
    return if (dot <= 0 || dot == fileName.lastIndex) null else fileName.substring(dot + 1).lowercase()
}
