package com.doronyg.photoarchivededuplicator.scan

import com.doronyg.photoarchivededuplicator.Diagnostics
import com.doronyg.photoarchivededuplicator.IoLane
import com.doronyg.photoarchivededuplicator.LogTags
import com.doronyg.photoarchivededuplicator.operations.BIN_DIRECTORY_MARKER_NAME
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.FileSystem
import okio.Path

internal fun Path.isBinMarkerMetadata(): Boolean =
    generateSequence(this) { it.parent }.any {
        it.name.equals(BIN_DIRECTORY_MARKER_NAME, ignoreCase = true) ||
            it.name.startsWith("$BIN_DIRECTORY_MARKER_NAME.", ignoreCase = true)
    }

/**
 * Presence, not JSON validity, establishes the exclusion. metadataOrNull observes the
 * marker entry itself (including a dangling symlink); it never opens or follows it.
 * Errors propagate instead of turning inaccessible markers into eligible content.
 */
internal class BinMarkerExclusions(
    private val fileSystem: FileSystem,
    private val filesystemLane: IoLane,
) {
    suspend fun discover(directories: Collection<Path>): Set<Path> = newPass().discover(directories)

    fun newPass() = Pass()

    /** Ancestor observations live only for this scan/discovery pass. */
    inner class Pass internal constructor() {
        private val observed = mutableMapOf<Path, Boolean>()

        suspend fun discover(directories: Collection<Path>): Set<Path> =
            filesystemLane.run("discover Bin directory markers") {
                val found = mutableSetOf<Path>()
                for (directory in directories) {
                    for (ancestor in generateSequence(directory.inventoryPath()) { it.parent }.toList().asReversed()) {
                        currentCoroutineContext().ensureActive()
                        val marked = observed.getOrPut(ancestor) {
                            val marker = ancestor / BIN_DIRECTORY_MARKER_NAME
                            Diagnostics.logger.operation(LogTags.FILE_SYSTEM, "metadataOrNull $marker") {
                                fileSystem.metadataOrNull(marker) != null
                            }
                        }
                        if (marked) {
                            found += ancestor
                            break
                        }
                    }
                }
                found.filterTo(mutableSetOf()) { directory ->
                    generateSequence(directory.parent) { it.parent }.none { it in found }
                }
            }
    }
}
