package com.doronyg.photoarchivededuplicator.operations

import com.doronyg.photoarchivededuplicator.IoLane
import com.doronyg.photoarchivededuplicator.Diagnostics
import com.doronyg.photoarchivededuplicator.operations.history.*
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import com.doronyg.photoarchivededuplicator.scan.isBinMarkerMetadata
import com.doronyg.photoarchivededuplicator.scan.isHistoryMetadata
import com.doronyg.photoarchivededuplicator.scan.isInTree
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okio.FileMetadata
import okio.FileSystem
import okio.Path
import okio.IOException
import okio.buffer
import okio.ByteString.Companion.encodeUtf8
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Executes only reviewed renames. No recovery, hashing, photo-content IO or copy/delete fallback. */
internal class FileOperationExecutor(
    private val filesystemLane: IoLane,
    private val moveIo: FileMoveIo,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val nextId: () -> String = { Uuid.random().toString() },
) {
    suspend fun execute(
        proposal: FileOperationProposal,
        binding: ArchiveBinding,
        validate: suspend () -> Unit,
        beforeMove: suspend (ProposedFileMove) -> Unit,
        recordMove: suspend (FileRecord) -> Unit,
        onProgress: (FileExecutionProgress) -> Unit = {},
    ): FileExecutionResult = withContext(dispatcher) {
        val plan = plan(proposal, binding)
        val archiveLock = binding.archiveRoot / "$ARCHIVE_MARKER_NAME.execution.lock"
        val binLock = binding.binRoot / "$HISTORY_DIRECTORY_NAME/.execution.lock"
        val locks = mutableListOf<Path>()
        var executionFailure: Throwable? = null
        var statisticsReady = false
        var completedMoves = 0L
        var logicalBytesMoved = 0L
        var statisticsError: String? = null
        suspend fun checkpoint(finished: Boolean) {
            try {
                filesystemLane.run("save operation statistics checkpoint") {
                    val path = plan.directory / "statistics.json"
                    val metadata = fileSystem.metadataOrNull(path)
                    if (metadata != null && (!metadata.isRegularFile || metadata.symlinkTarget != null))
                        throw IOException("Unsafe statistics checkpoint: $path")
                    val json = buildJsonObject {
                        put("formatVersion", 1)
                        put("operationId", proposal.id)
                        put("archiveId", binding.identity.archiveId)
                        put("updatedAt", timestamp(clock()))
                        put("completedMoves", completedMoves)
                        put("logicalBytesMoved", logicalBytesMoved)
                        put("finished", finished)
                    }
                    // This is a replaceable summary; the flushed journal remains authoritative.
                    fileSystem.sink(path).buffer().use { it.writeUtf8(json.toString()) }
                }
                statisticsError = null
            } catch (failure: IOException) {
                statisticsError = failure.message ?: failure.toString()
                Diagnostics.logger.w("FileOperations", "Statistics checkpoint failed: $statisticsError")
            }
        }
        val resultItems = try {
            filesystemLane.run("acquire archive and Bin execution locks") {
                verifyBinding(binding)
                for (lock in listOf(archiveLock, binLock)) {
                    require(fileSystem.metadataOrNull(lock) == null) { "Execution lock exists; inspect before retrying: $lock" }
                    fileSystem.createDirectory(lock, mustCreate = true)
                    locks += lock
                }
                safeDirectory(requireNotNull(plan.directory.parent), required = false)
                require(fileSystem.metadataOrNull(plan.directory) == null) {
                    "This operation already has execution records; never retry it automatically"
                }
            }
            validate()
            filesystemLane.run("preflight every reviewed source and filesystem") {
                verifyBinding(binding)
                plan.items.forEach { preflight(it, binding) }
            }
            BinDirectoryPreparer(filesystemLane, fileSystem, dispatcher).prepare(proposal, binding)
            filesystemLane.run("persist immutable execution manifest") {
                verifyBinding(binding)
                plan.items.forEach { preflight(it, binding, prepared = true) }
                safeDirectory(requireNotNull(plan.directory.parent), required = false)
                fileSystem.createDirectories(requireNotNull(plan.directory.parent))
                fileSystem.createDirectory(plan.directory, mustCreate = true)
                writeNew(plan.directory / "manifest.json", plan.manifest)
                writeNew(plan.directory / "events.jsonl", "")
                statisticsReady = true
            }
            val journal = Journal(plan.directory / "events.jsonl")
            val results = proposal.moves.map { FileMoveExecutionResult(it, FileMoveExecutionStatus.NOT_STARTED) }.toMutableList()
            onProgress(FileExecutionProgress(results.size))
            for ((index, item) in plan.items.withIndex()) {
                currentCoroutineContext().ensureActive()
                // Once an intent could exist, finish establishing its known outcome even
                // when the owning UI scope is cancelled. Cancellation is observed between files.
                val result = withContext(NonCancellable) {
                    executeItem(item, binding, journal, beforeMove, recordMove).also {
                        if (it.status == FileMoveExecutionStatus.COMPLETED) {
                            completedMoves++
                            logicalBytesMoved += item.file.sizeBytes
                            if (completedMoves % STATISTICS_BATCH_SIZE == 0L) checkpoint(finished = false)
                        }
                    }
                }
                results[index] = result
                val stop = result.status != FileMoveExecutionStatus.COMPLETED || result.postProcessingError != null
                onProgress(FileExecutionProgress(
                    total = results.size,
                    completed = results.count { it.status == FileMoveExecutionStatus.COMPLETED },
                    failed = results.count { it.status == FileMoveExecutionStatus.FAILED },
                    uncertain = results.count { it.status == FileMoveExecutionStatus.UNCERTAIN },
                    currentSource = item.move.sourcePath,
                    finished = stop || index == results.lastIndex,
                    logicalBytesMoved = logicalBytesMoved,
                ))
                currentCoroutineContext().ensureActive()
                if (stop) break
            }
            results.toList()
        } catch (failure: Throwable) {
            executionFailure = failure
            throw failure
        } finally {
            try {
                withContext(NonCancellable) {
                    try {
                        if (statisticsReady) checkpoint(finished = true)
                    } finally {
                        filesystemLane.run("release owned execution locks") {
                            var failure: Exception? = null
                            for (lock in locks.asReversed()) {
                                try {
                                    fileSystem.delete(lock, mustExist = true)
                                } catch (error: Exception) {
                                    if (failure == null) failure = error else failure.addSuppressed(error)
                                }
                            }
                            failure?.let { throw it }
                        }
                    }
                }
            } catch (cleanupFailure: Throwable) {
                val original = executionFailure
                if (original == null) throw cleanupFailure
                original.addSuppressed(cleanupFailure)
            }
        }
        FileExecutionResult(proposal.id, resultItems, statisticsError)
    }

    private suspend fun executeItem(
        item: Item, binding: ArchiveBinding, journal: Journal,
        beforeMove: suspend (ProposedFileMove) -> Unit, recordMove: suspend (FileRecord) -> Unit,
    ): FileMoveExecutionResult {
        val move = item.move
        val attempt = historyId(nextId())
        var intentFlushed = false
        var moveReturned = false
        var completed = false
        try {
            beforeMove(move)
            filesystemLane.run("move reviewed file with flushed journal") {
                verifyBinding(binding)
                preflight(item, binding, prepared = true)
                journal.append(item.id, attempt, "INTENT")
                intentFlushed = true
                moveIo.moveNoReplace(move.sourcePath, requireNotNull(move.destinationPath))
                moveReturned = true
                require(fileSystem.metadataOrNull(move.sourcePath) == null) { "Original still exists after rename" }
                safeDirectory(requireNotNull(move.destinationPath.parent), required = true)
                require(matches(fileSystem.metadataOrNull(move.destinationPath), item.file, creationTime = false)) {
                    "Destination metadata does not confirm the reviewed move"
                }
                journal.append(item.id, attempt, "COMPLETED")
                completed = true
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            // A thrown rename is never promoted to success, even if a destination
            // with matching size happens to exist. Only proven no-effect is FAILED.
            var detail = failure.message ?: failure.toString()
            failure.suppressedExceptions.forEach { detail += "; journal rollback failed: ${it.message}" }
            val noEffect = try {
                filesystemLane.run("verify failed move had no effect") {
                    safeDirectory(requireNotNull(move.sourcePath.parent), required = true)
                    safeDirectory(requireNotNull(move.destinationPath?.parent), required = false)
                    matches(fileSystem.metadataOrNull(move.sourcePath), item.file) &&
                        fileSystem.metadataOrNull(requireNotNull(move.destinationPath)) == null
                }
            } catch (verificationFailure: Exception) {
                if (verificationFailure is CancellationException) throw verificationFailure
                detail += "; outcome verification failed: ${verificationFailure.message}"
                false
            }
            var failed = noEffect && !moveReturned
            if (failed && intentFlushed) {
                try {
                    filesystemLane.run("record proven no-effect failure") {
                        journal.append(item.id, attempt, "FAILED", detail.take(ERROR_LIMIT))
                    }
                } catch (journalFailure: Exception) {
                    if (journalFailure is CancellationException) throw journalFailure
                    detail += "; failure journal not flushed: ${journalFailure.message}"
                    failed = false
                }
            }
            return FileMoveExecutionResult(move,
                if (failed) FileMoveExecutionStatus.FAILED else FileMoveExecutionStatus.UNCERTAIN, detail)
        }
        check(completed)
        return try {
            recordMove(item.file)
            FileMoveExecutionResult(move, FileMoveExecutionStatus.COMPLETED)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            FileMoveExecutionResult(move, FileMoveExecutionStatus.COMPLETED,
                postProcessingError = failure.message ?: failure.toString())
        }
    }

    private data class Item(
        val id: String, val move: ProposedFileMove, val file: FileRecord, val survivor: FileRecord,
        val group: FileOperationGroup, val groupKeys: List<String>,
    )
    private data class Plan(val directory: Path, val manifest: String, val items: List<Item>)

    private fun plan(proposal: FileOperationProposal, binding: ArchiveBinding): Plan {
        historyId(proposal.id)
        require(proposal.validationStatus == FileOperationValidationStatus.READY) { "Dry run needs attention" }
        require(proposal.binRoot == binding.binRoot) { "The Bin differs from the approved proposal" }
        require(proposal.archiveRoot == null || proposal.archiveRoot == binding.archiveRoot) { "Archive root differs from proposal" }
        require(binding.archiveRoot.isAbsolute && binding.archiveRoot == binding.archiveRoot.normalized() &&
            binding.binRoot.isAbsolute && binding.binRoot == binding.binRoot.normalized() &&
            binding.archiveRoot.parent != null && binding.binRoot.parent != null &&
            !binding.archiveRoot.isInTree(binding.binRoot)) { "Unsafe archive/Bin roots" }
        val files = proposal.groups.flatMap { group -> group.members.map { group to it.file } }.groupBy { it.second.path }
        fun snapshot(path: Path): Pair<FileOperationGroup, FileRecord> {
            val entries = requireNotNull(files[path]) { "Reviewed file snapshot is missing: $path" }
            require(entries.map { it.second }.distinct().size == 1) { "Conflicting reviewed file snapshots" }
            return entries.first()
        }
        val sources = proposal.moves.map { it.sourcePath }.toSet()
        require(sources.size == proposal.moves.size) { "Duplicate sources" }
        val items = proposal.moves.mapIndexed { index, move ->
            val survivorPath = requireNotNull(move.survivorPath)
            for (path in listOf(move.sourcePath, survivorPath)) {
                archiveRelativePath(path, binding.archiveRoot)
                require(!path.isInTree(binding.binRoot) && !path.isHistoryMetadata() && !path.isBinMarkerMetadata()) {
                    "Reviewed path targets Bin or reserved metadata"
                }
            }
            require(survivorPath !in sources) { "Retained replacement is also selected for movement" }
            val destination = requireNotNull(operationDestinationPath(move.sourcePath, binding.binRoot))
            require(move.destinationPath == destination && move.relativePath == destination.relativeTo(binding.binRoot)) {
                "Unsafe or changed destination mapping"
            }
            val (group, file) = snapshot(move.sourcePath)
            require(file.sizeBytes >= 0 && file.sizeBytes == move.sizeBytes)
            Item("item-${index + 1}", move, file, snapshot(survivorPath).second, group,
                files.getValue(move.sourcePath).map { it.first.key }.distinct())
        }
        val manifest = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("formatVersion", 1)
            put("operationId", proposal.id)
            put("archiveId", binding.identity.archiveId)
            put("createdAt", timestamp(proposal.createdAt))
            put("groups", buildJsonArray {
                for (group in proposal.groups) add(buildJsonObject {
                    put("key", group.key)
                    put("categoryId", group.categoryId)
                    put("categoryName", group.categoryName)
                    put("explanation", group.explanation)
                    put("duplicateConfidence", group.duplicateConfidence)
                })
            })
            put("items", buildJsonArray {
                for (item in items) add(buildJsonObject {
                    put("itemId", item.id)
                    put("originalRelativePath", archiveRelativePath(item.file.path, binding.archiveRoot))
                    put("binRelativePath", archiveRelativePath(requireNotNull(item.move.destinationPath), binding.binRoot))
                    put("sizeBytes", item.file.sizeBytes)
                    put("modifiedAtMillis", item.file.modifiedAt)
                    put("createdAtMillis", item.file.createdAt)
                    put("categoryId", item.group.categoryId)
                    put("explanation", item.group.explanation)
                    put("groupKeys", JsonArray(item.groupKeys.map(::JsonPrimitive)))
                    put("retainedRelativePath", archiveRelativePath(item.survivor.path, binding.archiveRoot))
                })
            })
        })
        require(manifest.encodeUtf8().size <= MANIFEST_LIMIT) { "Manifest exceeds history reader limit" }
        // Validate portable paths and case-folded collisions using the actual history reader.
        parseHistoryOperation(proposal.id, binding.identity.archiveId, manifest, "")
        // At most an intent and a terminal event per item. Reserve worst-case escaped
        // errors and 128-byte injected IDs rather than relying on typical event sizes.
        require(items.size.toLong() * (2 * 2048L + ERROR_LIMIT * 6L) <= JOURNAL_LIMIT) {
            "Execution journal could exceed history reader limit"
        }
        return Plan(binding.binRoot / "$HISTORY_DIRECTORY_NAME/operations/${proposal.id}", manifest, items)
    }

    private fun preflight(item: Item, binding: ArchiveBinding, prepared: Boolean = false) {
        for (file in listOf(item.file, item.survivor)) {
            safeDirectory(requireNotNull(file.path.parent), required = true)
            require(matches(fileSystem.metadataOrNull(file.path), file)) { "Reviewed file changed or is unavailable: ${file.path}" }
            // An excluded directory marker on the route must never become a photo source.
            var directory: Path? = file.path.parent
            while (directory != null && directory.isInTree(binding.archiveRoot)) {
                require(fileSystem.metadataOrNull(directory / BIN_DIRECTORY_MARKER_NAME) == null) {
                    "Reviewed file is inside a marked Bin directory"
                }
                directory = directory.parent
            }
        }
        val destination = requireNotNull(item.move.destinationPath)
        safeDirectory(requireNotNull(destination.parent), required = false)
        require(fileSystem.metadataOrNull(destination) == null) { "Destination exists: $destination" }
        if (prepared) verifyDestinationMarkers(item, binding)
        var existing = requireNotNull(destination.parent)
        while (fileSystem.metadataOrNull(existing) == null) existing = requireNotNull(existing.parent)
        require(moveIo.sameFileSystem(item.file.path, existing)) { "Cross-filesystem moves are not permitted" }
    }

    private fun verifyDestinationMarkers(item: Item, binding: ArchiveBinding) {
        var destination = requireNotNull(item.move.destinationPath?.parent)
        var original = item.file.path.parent
        while (true) {
            val markerPath = destination / BIN_DIRECTORY_MARKER_NAME
            val metadata = fileSystem.metadataOrNull(markerPath)
            require(metadata?.isRegularFile == true && metadata.symlinkTarget == null) { "Unsafe or missing Bin mapping: $markerPath" }
            val expected = BinDirectoryMarker(binding.identity,
                if (destination == binding.binRoot) "" else archiveRelativePath(destination, binding.binRoot),
                if (destination == binding.binRoot) null else original?.toString(),
                original?.takeIf { it.isInTree(binding.archiveRoot) }?.let {
                    if (it == binding.archiveRoot) "" else archiveRelativePath(it, binding.archiveRoot)
                })
            require(decodeBinDirectoryMarker(readMarker(markerPath)).matchesMapping(expected)) {
                "Bin directory mapping changed: $markerPath"
            }
            if (destination == binding.binRoot) break
            destination = requireNotNull(destination.parent)
            original = original?.parent
        }
    }

    private fun matches(metadata: FileMetadata?, file: FileRecord, creationTime: Boolean = true): Boolean =
        metadata?.isRegularFile == true && metadata.symlinkTarget == null && metadata.size == file.sizeBytes &&
            (file.modifiedAt == null || metadata.lastModifiedAtMillis == file.modifiedAt) &&
            (!creationTime || file.createdAt == null || metadata.createdAtMillis == file.createdAt)

    private fun safeDirectory(path: Path, required: Boolean) {
        for (part in generateSequence(path) { it.parent }) {
            val metadata = fileSystem.metadataOrNull(part)
            require(metadata == null || metadata.isDirectory && metadata.symlinkTarget == null) { "Unsafe directory ancestor: $part" }
            require(part != path || !required || metadata != null) { "Required directory is missing: $path" }
        }
    }

    private fun verifyBinding(binding: ArchiveBinding) {
        for ((path, bin) in listOf(binding.archiveRoot / ARCHIVE_MARKER_NAME to false,
            binding.binRoot / "$HISTORY_DIRECTORY_NAME/bin.json" to true)) {
            safeDirectory(requireNotNull(path.parent), required = true)
            val metadata = fileSystem.metadataOrNull(path)
            require(metadata?.isRegularFile == true && metadata.symlinkTarget == null) { "Unsafe or missing identity: $path" }
            require(parseIdentity(readMarker(path), bin) == binding.identity) { "Archive/Bin identity changed" }
        }
    }

    private fun readMarker(path: Path) = fileSystem.source(path).buffer().use {
        require(!it.request(64L * 1024 + 1)) { "Marker exceeds reader limit: $path" }
        it.readUtf8()
    }

    private fun writeNew(path: Path, text: String) {
        fileSystem.sink(path, mustCreate = true).buffer().use { it.writeUtf8(text); it.flush() }
        moveIo.flushFile(path)
    }

    private inner class Journal(private val path: Path) {
        private var sequence = 0L
        private var bytes = 0L
        fun append(item: String, attempt: String, state: String, error: String? = null) {
            val line = buildJsonObject {
                put("formatVersion", 1)
                put("eventId", historyId(nextId()))
                put("sequence", sequence + 1)
                put("itemId", item)
                put("attemptId", attempt)
                put("action", "MOVE")
                put("state", state)
                put("occurredAt", timestamp(clock()))
                put("error", error)
            }.toString() + "\n"
            val size = line.encodeUtf8().size
            require(bytes + size <= JOURNAL_LIMIT) { "Journal exceeds reader limit" }
            val previousBytes = bytes
            try {
                fileSystem.appendingSink(path).buffer().use { it.writeUtf8(line); it.flush() }
                moveIo.flushFile(path)
                sequence++
                bytes += size
            } catch (failure: Exception) {
                // An unflushed terminal record must not advertise certainty on a later
                // read. Best-effort truncation preserves the already-flushed INTENT.
                if (state != "INTENT") {
                    try {
                        fileSystem.openReadWrite(path, mustExist = true).use { it.resize(previousBytes) }
                        moveIo.flushFile(path)
                    } catch (rollbackFailure: Exception) {
                        failure.addSuppressed(rollbackFailure)
                    }
                }
                throw failure
            }
        }
    }

    private fun timestamp(millis: Long) = Instant.fromEpochMilliseconds(millis).toString()

    private companion object {
        const val MANIFEST_LIMIT = 8L * 1024 * 1024
        const val JOURNAL_LIMIT = 16L * 1024 * 1024
        const val ERROR_LIMIT = 2048
        const val STATISTICS_BATCH_SIZE = 20
    }
}
