package com.doronyg.photoarchivededuplicator.scan

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
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
 * Okio file system calls are blocking, so they run on [ioDispatcher];
 * [repository] moves its own SQLite work onto its IO dispatcher.
 */
class DirectoryScanner(
    private val fileSystem: FileSystem,
    private val repository: DirectoryRepository,
    private val config: ScanConfig = ScanConfig(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    /** Starts a new (re)scan of [root]; previously recorded data is reconciled, not discarded. */
    suspend fun startScan(root: Path) {
        repository.seedRoot(root)
        resume()
    }

    /** Continues any unfinished scan, including directories interrupted mid-listing. */
    suspend fun resume() {
        repository.resetInterrupted()
        while (true) {
            val batch = repository.fetchPendingBatch(config.batchSize)
            if (batch.isEmpty()) break
            for (dir in batch) {
                currentCoroutineContext().ensureActive()
                scanDirectory(dir)
            }
        }
    }

    private suspend fun scanDirectory(dir: Path) {
        repository.markInProgress(dir)
        val listing = try {
            withContext(ioDispatcher) { readListing(dir) }
        } catch (e: IOException) {
            val record = repository.recordFailure(dir, config.maxAttempts)
            if (record.status == DirectoryStatus.PENDING) delay(backoff(record.retryCount))
            return
        }
        repository.recordListing(dir, listing, clock())
    }

    /** Blocking; call on [ioDispatcher]. */
    private fun readListing(dir: Path): DirectoryListing {
        val subdirectories = mutableListOf<DirectoryListing.Entry>()
        val files = mutableListOf<DirectoryListing.Entry>()
        for (child in fileSystem.list(dir)) {
            // Entries can vanish between list() and metadata(); skip them
            val metadata = fileSystem.metadataOrNull(child) ?: continue
            if (metadata.symlinkTarget != null) continue
            val entry = DirectoryListing.Entry(child, metadata.size ?: 0L, metadata.lastModifiedAtMillis)
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
