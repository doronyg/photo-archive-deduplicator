package com.doronyg.photoarchivededuplicator.scan

import com.doronyg.photoarchivededuplicator.Diagnostics
import com.doronyg.photoarchivededuplicator.IoLane
import com.doronyg.photoarchivededuplicator.LogTags
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okio.FileSystem
import okio.IOException
import okio.Path
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

data class ScanConfig(
    val batchSize: Int = 100,
    /** Total listing attempts per directory before it is marked FAILED. */
    val maxAttempts: Int = 3,
    val initialBackoff: Duration = 1.seconds,
    val maxBackoff: Duration = 1.minutes,
    /** Directory names never descended into. Defaults are Synology DSM system folders. */
    val excludedDirectoryNames: Set<String> = SYNOLOGY_SYSTEM_DIRECTORIES,
) {
    init {
        require(batchSize > 0) { "batchSize must be positive" }
        require(maxAttempts > 0) { "maxAttempts must be positive" }
    }

    companion object {
        val SYNOLOGY_SYSTEM_DIRECTORIES = setOf("@eaDir", "#recycle", "#snapshot")
    }
}

/**
 * Breadth-first, database-backed directory scanner. All progress lives in [repository],
 * so a scan can be cancelled (paused) at any point and continued with [resume].
 * Symlinks are never followed.
 *
 * Okio file system calls are blocking, so each directory listing holds [filesystemLane];
 * [repository] commits the complete directory on the independent database lane.
 * Cancellation can discard this directory's uncommitted listing; resume reads it again.
 */
internal class DirectoryScanner(
    private val fileSystem: FileSystem,
    private val repository: DirectoryRepository,
    private val config: ScanConfig = ScanConfig(),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val filesystemLane: IoLane = IoLane("filesystem-test", ioDispatcher),
) : InventoryScanner {
    override suspend fun startScan(root: Path, onEvent: suspend (ScanEvent) -> Unit) = repository.scanning {
        repository.seedRoot(root)
        resumeScan(root.inventoryPath(), onEvent)
    }

    /** Continues only the selected logical scan, leaving other roots' queues intact. */
    override suspend fun resume(onEvent: suspend (ScanEvent) -> Unit) = repository.scanning {
        resumeScan(repository.resumableScanSession().root, onEvent)
    }

    private suspend fun resumeScan(root: Path, onEvent: suspend (ScanEvent) -> Unit) {
        repository.resetInterrupted(root)
        onEvent(ScanEvent.Started(pendingDirectories = repository.pendingScanCount(root)))
        while (true) {
            val batch = repository.fetchPendingBatch(config.batchSize, root)
            if (batch.isEmpty()) break
            for (dir in batch) {
                currentCoroutineContext().ensureActive()
                if (repository.isExcluded(dir)) continue
                onEvent(ScanEvent.DirectoryStarted(dir))
                scanDirectory(dir, onEvent)
            }
        }
    }

    private suspend fun scanDirectory(dir: Path, onEvent: suspend (ScanEvent) -> Unit) {
        repository.markInProgress(dir)
        val exclusions = repository.exclusions()
        val listing = try {
            filesystemLane.run("readListing $dir") { readListing(dir, exclusions) }
        } catch (e: IOException) {
            val record = repository.recordFailure(dir, config.maxAttempts)
            val willRetry = record.status == DirectoryStatus.PENDING
            onEvent(ScanEvent.DirectoryFailed(dir, record.retryCount, willRetry, e.message))
            if (willRetry) delay(backoff(record.retryCount))
            return
        }
        repository.recordListing(dir, listing, clock())
        onEvent(ScanEvent.DirectoryCompleted(dir, listing.subdirectories.size, listing.files.size))
    }

    /** Blocking; call inside the filesystem lane, once per directory. */
    private suspend fun readListing(dir: Path, exclusions: List<DirectoryExclusion>): DirectoryListing {
        val subdirectories = mutableListOf<DirectoryListing.Entry>()
        val files = mutableListOf<DirectoryListing.Entry>()
        for (child in Diagnostics.logger.operation(LogTags.FILE_SYSTEM, "list $dir") { fileSystem.list(dir) }) {
            currentCoroutineContext().ensureActive()
            if (exclusions.any { child.isInTree(it.path) }) continue
            // Entries can vanish between list() and metadata(); skip them
            val metadata = Diagnostics.logger.operation(LogTags.FILE_SYSTEM, "metadataOrNull $child") {
                fileSystem.metadataOrNull(child)
            } ?: continue
            if (metadata.symlinkTarget != null) continue
            val entry = DirectoryListing.Entry(
                child, metadata.size ?: 0L, metadata.lastModifiedAtMillis, metadata.createdAtMillis,
            )
            when {
                metadata.isDirectory -> if (child.name !in config.excludedDirectoryNames) subdirectories += entry
                metadata.isRegularFile -> files += entry
            }
        }
        return DirectoryListing(subdirectories, files)
    }

    private fun backoff(attempts: Int): Duration {
        val factor = 1L shl (attempts - 1).coerceIn(0, 30)
        return (config.initialBackoff * factor.toDouble()).coerceAtMost(config.maxBackoff)
    }
}
