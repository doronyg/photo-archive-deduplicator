package com.doronyg.photoarchivededuplicator.operations

import com.doronyg.photoarchivededuplicator.IoLane
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassification
import com.doronyg.photoarchivededuplicator.operations.history.ARCHIVE_MARKER_NAME
import com.doronyg.photoarchivededuplicator.operations.history.ArchiveBinding
import com.doronyg.photoarchivededuplicator.operations.history.HISTORY_DIRECTORY_NAME
import com.doronyg.photoarchivededuplicator.operations.history.archiveRelativePath
import com.doronyg.photoarchivededuplicator.operations.history.parseIdentity
import com.doronyg.photoarchivededuplicator.operations.history.resolvePortablePath
import com.doronyg.photoarchivededuplicator.scan.isInTree
import com.doronyg.photoarchivededuplicator.scan.isHistoryMetadata
import com.doronyg.photoarchivededuplicator.scan.isBinMarkerMetadata
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.FileMetadata
import okio.FileSystem
import okio.Path
import okio.buffer

internal data class BinDirectoryPreparationResult(
    val createdDirectories: Int,
    val writtenMarkers: Int,
    val checkedDirectories: Int,
)

/** Creates directories and mapping metadata only. It never opens, copies, renames or deletes photos. */
internal class BinDirectoryPreparer(
    private val filesystemLane: IoLane,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend fun prepare(proposal: FileOperationProposal, binding: ArchiveBinding): BinDirectoryPreparationResult =
        withContext(dispatcher) {
            val plan = plan(proposal, binding)
            filesystemLane.run("prepare Bin directories and mapping markers") {
                val observations = mutableMapOf<Path, FileMetadata?>()
                fun inspect(path: Path): FileMetadata? = if (observations.containsKey(path)) observations[path]
                    else fileSystem.metadataOrNull(path).also { observations[path] = it }
                fun directory(path: Path, required: Boolean = false) {
                    for (part in generateSequence(path) { it.parent }) {
                        val metadata = inspect(part)
                        require(metadata == null || metadata.isDirectory && metadata.symlinkTarget == null) {
                            "Unsafe Bin directory path: $part"
                        }
                        require(part != path || !required || metadata != null) { "Directory is missing: $path" }
                    }
                }
                fun identity(path: Path, bin: Boolean = false) {
                    directory(requireNotNull(path.parent), required = true)
                    val metadata = inspect(path)
                    require(metadata?.isRegularFile == true && metadata.symlinkTarget == null) {
                        "Identity metadata is missing or unsafe: $path"
                    }
                    require(parseIdentity(readMarker(path), bin) == binding.identity) {
                        "Archive or Bin identity changed: $path"
                    }
                }
                directory(binding.archiveRoot, required = true)
                directory(binding.binRoot, required = true)
                identity(binding.archiveRoot / ARCHIVE_MARKER_NAME)
                val history = binding.binRoot / HISTORY_DIRECTORY_NAME
                identity(history / "bin.json", bin = true)
                val lock = history / ".directory-preparation.lock"
                require(inspect(lock) == null) { "Bin preparation lock exists; inspect it before retrying: $lock" }
                fileSystem.createDirectory(lock, mustCreate = true)
                try {
                    for (move in proposal.moves) {
                        require(!move.sourcePath.isHistoryMetadata() && !move.sourcePath.isBinMarkerMetadata()) {
                            "A proposed path targets reserved application metadata"
                        }
                        require(inspect(requireNotNull(move.destinationPath)) == null) {
                            "Proposed destination already exists: ${move.destinationPath}"
                        }
                    }
                    val existing = mutableSetOf<Path>()
                    for ((path, expected) in plan) {
                        currentCoroutineContext().ensureActive()
                        directory(path)
                        val marker = path / BIN_DIRECTORY_MARKER_NAME
                        val metadata = inspect(marker) ?: continue
                        require(metadata.isRegularFile && metadata.symlinkTarget == null) { "Unsafe Bin marker: $marker" }
                        require(decodeBinDirectoryMarker(readMarker(marker)).matchesMapping(expected)) {
                            "Bin directory mapping does not match; refusing to overwrite: $marker"
                        }
                        existing += path
                    }
                    var created = 0
                    var written = 0
                    // The root marker is first, so an interrupted child creation is still excluded.
                    for ((path, marker) in plan) {
                        currentCoroutineContext().ensureActive()
                        if (inspect(path) == null) {
                            fileSystem.createDirectory(path, mustCreate = true)
                            created++
                        }
                        if (path !in existing) {
                            // Exclusive creation never overwrites a marker. A failed write is left
                            // excluded and must be inspected, rather than silently repaired.
                            fileSystem.sink(path / BIN_DIRECTORY_MARKER_NAME, mustCreate = true).buffer().use {
                                it.writeUtf8(encodeBinDirectoryMarker(marker))
                                it.flush()
                            }
                            written++
                        }
                    }
                    BinDirectoryPreparationResult(created, written, plan.size)
                } finally {
                    fileSystem.delete(lock, mustExist = true)
                }
            }
        }

    private fun plan(proposal: FileOperationProposal, binding: ArchiveBinding): Map<Path, BinDirectoryMarker> {
        require(proposal.validationStatus == FileOperationValidationStatus.READY) { "The dry run needs attention" }
        require(proposal.binRoot == binding.binRoot) { "The configured Bin differs from the dry run" }
        require(binding.archiveRoot.isAbsolute && binding.archiveRoot == binding.archiveRoot.normalized() &&
            binding.binRoot.isAbsolute && binding.binRoot == binding.binRoot.normalized() &&
            binding.binRoot.parent != null && !binding.archiveRoot.isInTree(binding.binRoot)) { "Unsafe archive/Bin roots" }
        require(proposal.groups.none { group -> group.members.any { it.classification == DirectoryClassification.PROTECTED } }) {
            "The proposal contains protected members"
        }
        val sources = proposal.moves.map { it.sourcePath }.toSet()
        require(sources.size == proposal.moves.size) { "Duplicate proposed source" }
        val destinations = mutableSetOf<String>()
        val mappings = mutableMapOf<Path, BinDirectoryMarker>()
        for (move in proposal.moves) {
            require(move.sourcePath.isInTree(binding.archiveRoot) && move.sourcePath != binding.archiveRoot) {
                "Source is outside the registered archive: ${move.sourcePath}"
            }
            require(move.survivorPath?.isInTree(binding.archiveRoot) == true && move.survivorPath !in sources &&
                !move.survivorPath.isInTree(binding.binRoot)) { "Retained replacement is not safely mapped" }
            val destination = requireNotNull(operationDestinationPath(move.sourcePath, binding.binRoot))
            require(move.destinationPath == destination && move.relativePath == destination.relativeTo(binding.binRoot)) {
                "Destination does not preserve the reviewed full source path"
            }
            val relative = archiveRelativePath(destination, binding.binRoot)
            require(destinations.add(relative.lowercase())) { "Case-colliding proposed destinations" }
            var original = move.sourcePath.parent
            var path = requireNotNull(destination.parent)
            while (true) {
                val binRelative = if (path == binding.binRoot) "" else archiveRelativePath(path, binding.binRoot)
                val originalRelative = original?.takeIf { it.isInTree(binding.archiveRoot) }?.let {
                    if (it == binding.archiveRoot) "" else archiveRelativePath(it, binding.archiveRoot)
                }
                val marker = BinDirectoryMarker(binding.identity, binRelative,
                    if (path == binding.binRoot) null else original?.toString(), originalRelative)
                require(mappings.put(path, marker).let { it == null || it == marker }) { "Conflicting directory mappings" }
                if (path == binding.binRoot) break
                path = requireNotNull(path.parent)
                original = original?.parent
            }
        }
        val folded = mutableMapOf<String, Path>()
        for (path in mappings.keys) {
            require(folded.put(path.toString().lowercase(), path).let { it == null || it == path }) {
                "Case-colliding directory paths"
            }
            if (path != binding.binRoot)
                resolvePortablePath(binding.binRoot, path.relativeTo(binding.binRoot).segments.joinToString("/"))
        }
        require(proposal.moves.none { requireNotNull(it.destinationPath).toString().lowercase() in folded }) {
            "File/directory destination collision"
        }
        return mappings.toList().sortedBy { it.first.segments.size }.toMap()
    }

    private fun readMarker(path: Path): String = fileSystem.source(path).buffer().use {
        require(!it.request(64L * 1024 + 1)) { "Bin metadata exceeds 64 KiB: $path" }
        it.readUtf8()
    }
}
