package com.doronyg.photoarchivededuplicator.scan

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.Path
import kotlin.time.Clock

/** One file as reported by a platform media index (e.g. Android MediaStore). */
data class MediaItem(val path: Path, val sizeBytes: Long, val modifiedAt: Long?)

/** A platform media index. Implementations do their own blocking work off the caller's thread. */
fun interface MediaSource {
    suspend fun items(): List<MediaItem>
}

/**
 * Builds the inventory from a [MediaSource] instead of walking the file system.
 * Items are grouped into folders (creating intermediate folders down from the root),
 * then each folder is recorded like a [DirectoryScanner] listing, so browsing works the same.
 * A media index is local and quick to query, so every run re-reads it in full rather than
 * tracking resumable per-folder work.
 */
class MediaIndexScanner(
    private val source: MediaSource,
    private val repository: DirectoryRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : InventoryScanner {
    override suspend fun startScan(root: Path, onEvent: suspend (ScanEvent) -> Unit) {
        repository.seedRoot(root)
        val filesByFolder = withContext(ioDispatcher) { source.items() }
            .filter { it.path.isInside(root) }
            .groupBy { it.path.parent!! }
        val subfoldersByFolder = subfolderTree(root, filesByFolder.keys)

        onEvent(ScanEvent.Started(pendingDirectories = 1))
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val folder = queue.removeFirst()
            val subfolders = subfoldersByFolder[folder].orEmpty().sorted()
            val files = filesByFolder[folder].orEmpty()
            onEvent(ScanEvent.DirectoryStarted(folder))
            repository.markInProgress(folder)
            val listing = DirectoryListing(
                subdirectories = subfolders.map { DirectoryListing.Entry(it, sizeBytes = 0, modifiedAt = null) },
                files = files.map { DirectoryListing.Entry(it.path, it.sizeBytes, it.modifiedAt) },
            )
            repository.recordListing(folder, listing, clock())
            onEvent(ScanEvent.DirectoryCompleted(folder, subfolders.size, files.size))
            queue.addAll(subfolders)
        }
    }

    /** Rescans every root scanned before. */
    override suspend fun resume(onEvent: suspend (ScanEvent) -> Unit) {
        for (root in repository.childOverviews(parent = null)) {
            startScan(root.directory.path, onEvent)
        }
    }

    /** Maps each folder to its direct subfolders, for every folder between [root] and [folders]. */
    private fun subfolderTree(root: Path, folders: Set<Path>): Map<Path, Set<Path>> {
        val tree = mutableMapOf<Path, MutableSet<Path>>()
        for (folder in folders) {
            var child = folder
            while (child != root) {
                val parent = child.parent!!
                // Stop once this branch is already linked up to the root
                if (!tree.getOrPut(parent) { mutableSetOf() }.add(child)) break
                child = parent
            }
        }
        return tree
    }

    private fun Path.isInside(root: Path): Boolean = generateSequence(parent) { it.parent }.any { it == root }
}
