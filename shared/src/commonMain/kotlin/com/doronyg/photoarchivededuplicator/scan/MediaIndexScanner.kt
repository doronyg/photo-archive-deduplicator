package com.doronyg.photoarchivededuplicator.scan

import com.doronyg.photoarchivededuplicator.LogTags

import com.doronyg.photoarchivededuplicator.Diagnostics

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
internal class MediaIndexScanner(
    private val source: MediaSource,
    private val repository: DirectoryRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : InventoryScanner {
    override suspend fun startScan(root: Path, onEvent: suspend (ScanEvent) -> Unit) = repository.scanning {
        scanRoot(root, onEvent)
    }

    private suspend fun scanRoot(root: Path, onEvent: suspend (ScanEvent) -> Unit) {
        val scanRoot = root.inventoryPath()
        var exclusions = repository.exclusions()
        if (scanRoot.isHistoryMetadata() || scanRoot.isBinMarkerMetadata() ||
            exclusions.any { scanRoot.isInTree(it.path) }) return
        val markers = repository.newBinMarkerPass()
        repository.discoverBinMarkers(listOf(scanRoot), markers)
        exclusions = repository.exclusions()
        if (exclusions.any { scanRoot.isInTree(it.path) }) return
        repository.seedRoot(scanRoot)
        val items = Diagnostics.logger.operation(LogTags.MEDIA_INDEX, "source.items dispatch-to=$ioDispatcher") {
            withContext(ioDispatcher) { source.items() }
        }
            .map { it.copy(path = it.path.normalized()) }
            .filter { !it.path.isHistoryMetadata() && !it.path.isBinMarkerMetadata() &&
                it.path != scanRoot && it.path.isInTree(scanRoot) &&
                exclusions.none { excluded -> it.path.isInTree(excluded.path) } }
        // MediaStore does not index JSON markers. Probe accessible filesystem ancestors;
        // inaccessible marker metadata fails the scan rather than admitting Bin photos.
        repository.discoverBinMarkers(items.mapNotNull { it.path.parent }.distinct(), markers)
        exclusions = repository.exclusions()
        if (exclusions.any { scanRoot.isInTree(it.path) }) return
        val filesByFolder = items.filter {
                exclusions.none { excluded -> it.path.isInTree(excluded.path) } }
            .groupBy { it.path.parent!! }
        val subfoldersByFolder = subfolderTree(scanRoot, filesByFolder.keys)

        onEvent(ScanEvent.Started(pendingDirectories = 1))
        val queue = ArrayDeque(listOf(scanRoot))
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

    /** The platform index is re-read, but only the selected logical root is reconciled. */
    override suspend fun resume(onEvent: suspend (ScanEvent) -> Unit) = repository.scanning {
        scanRoot(repository.resumableScanSession().root, onEvent)
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
}
