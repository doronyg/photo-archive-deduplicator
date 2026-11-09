package com.doronyg.photoarchivededuplicator.operations

import com.doronyg.photoarchivededuplicator.IoLane
import com.doronyg.photoarchivededuplicator.Diagnostics
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassification
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
        val reviewed = review(proposal, binding)
        val plans = plan(reviewed, binding)
        val results = proposal.moves.map { move ->
            reviewed.executionEligibility.rejected[move.sourcePath]?.let {
                FileMoveExecutionResult(move, FileMoveExecutionStatus.FAILED, findingsText(it))
            } ?: FileMoveExecutionResult(move, FileMoveExecutionStatus.NOT_STARTED)
        }.toMutableList()
        var failedMoves = results.count { it.status == FileMoveExecutionStatus.FAILED }
        var remainingMoves = results.size - failedMoves
        val indices = proposal.moves.withIndex().groupBy({ it.value.sourcePath }, { it.index })
        val groupsBySource = proposal.groups.flatMap { group ->
            group.members.map { it.file.path to group.key }
        }.groupBy({ it.first }, { it.second })
        val sourcesByGroup = proposal.groups.associate { group ->
            group.key to group.members.map { it.file.path }.filter { it in indices }
        }
        val rejectedGroups = mutableMapOf<String, String>()
        fun rejectGroups(keys: List<String>, detail: String) {
            val queue = ArrayDeque(keys)
            while (queue.isNotEmpty()) {
                val key = queue.removeFirst()
                if (rejectedGroups.put(key, detail) != null) continue
                for (source in sourcesByGroup[key].orEmpty()) {
                    for (related in groupsBySource[source].orEmpty()) {
                        if (related !in rejectedGroups) queue.addLast(related)
                    }
                    for (index in indices[source].orEmpty()) {
                        if (results[index].status == FileMoveExecutionStatus.NOT_STARTED) {
                            results[index] = FileMoveExecutionResult(proposal.moves[index], FileMoveExecutionStatus.FAILED, detail)
                            failedMoves++
                            remainingMoves--
                        }
                    }
                }
            }
        }
        val archiveLock = binding.archiveRoot / "$ARCHIVE_MARKER_NAME.execution.lock"
        val binLock = binding.binRoot / "$HISTORY_DIRECTORY_NAME/.execution.lock"
        val locks = mutableListOf<Path>()
        var executionFailure: Throwable? = null
        var statisticsReady = false
        var activePlan: Plan? = null
        var batchCompleted = 0L
        var batchBytes = 0L
        var completedMoves = 0L
        var uncertainMoves = 0
        var logicalBytesMoved = 0L
        val statisticsErrors = linkedMapOf<String, String>()
        suspend fun checkpoint(finished: Boolean) {
            val plan = requireNotNull(activePlan)
            try {
                filesystemLane.run("save operation statistics checkpoint") {
                    val path = plan.directory / "statistics.json"
                    val metadata = fileSystem.metadataOrNull(path)
                    if (metadata != null && (!metadata.isRegularFile || metadata.symlinkTarget != null))
                        throw IOException("Unsafe statistics checkpoint: $path")
                    val json = buildJsonObject {
                        put("formatVersion", 1)
                        put("operationId", plan.directory.name)
                        put("archiveId", binding.identity.archiveId)
                        put("updatedAt", timestamp(clock()))
                        put("completedMoves", batchCompleted)
                        put("logicalBytesMoved", batchBytes)
                        put("finished", finished)
                    }
                    // This is a replaceable summary; the flushed journal remains authoritative.
                    fileSystem.sink(path).buffer().use { it.writeUtf8(json.toString()) }
                }
                statisticsErrors.remove(plan.directory.name)
            } catch (failure: IOException) {
                val detail = failure.message ?: failure.toString()
                statisticsErrors[plan.directory.name] = detail
                Diagnostics.logger.w("FileOperations", "Statistics checkpoint failed: $detail")
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
                val operations = binding.binRoot / "$HISTORY_DIRECTORY_NAME/operations"
                safeDirectory(operations, required = false)
                val runs = binding.binRoot / "$HISTORY_DIRECTORY_NAME/runs"
                safeDirectory(runs, required = false)
                require(fileSystem.metadataOrNull(runs / proposal.id) == null &&
                    fileSystem.metadataOrNull(operations / proposal.id) == null &&
                    plans.all { fileSystem.metadataOrNull(it.directory) == null }) {
                    "This operation already has execution records; never retry it automatically"
                }
                // Persistent, exclusive reservation is deliberately never taken over or removed.
                fileSystem.createDirectories(runs)
                fileSystem.createDirectory(runs / proposal.id, mustCreate = true)
            }
            validate()
            if (reviewed.canExecute) {
                val preparation = BinDirectoryPreparer(filesystemLane, fileSystem, dispatcher)
                    .prepareEligibleGroups(reviewed.copy(moves = reviewed.executionEligibility.moves), binding)
                preparation.rejected.forEach { (key, findings) -> rejectGroups(listOf(key), findingsText(findings)) }
            }
            filesystemLane.run("persist immutable execution manifest") {
                verifyBinding(binding)
                // Persist every validated batch before the first photo move.
                for (plan in plans) {
                    safeDirectory(requireNotNull(plan.directory.parent), required = false)
                    fileSystem.createDirectories(requireNotNull(plan.directory.parent))
                    fileSystem.createDirectory(plan.directory, mustCreate = true)
                    writeNew(plan.directory / "manifest.json", plan.manifest)
                    writeNew(plan.directory / "events.jsonl", "")
                }
            }
            onProgress(FileExecutionProgress(total = results.size, failed = failedMoves,
                finished = remainingMoves == 0))
            batches@ for (plan in plans) {
                activePlan = plan
                statisticsReady = true
                batchCompleted = 0
                batchBytes = 0
                val journal = Journal(plan.directory / "events.jsonl")
                for (item in plan.items) {
                    val index = indices.getValue(item.move.sourcePath).single()
                    if (results[index].status != FileMoveExecutionStatus.NOT_STARTED) continue
                    currentCoroutineContext().ensureActive()
                    // Once an intent could exist, finish establishing its known outcome even
                    // when the owning UI scope is cancelled. Cancellation is observed between files.
                    val result = withContext(NonCancellable) {
                        executeItem(item, binding, journal, beforeMove, recordMove).also {
                            if (it.status == FileMoveExecutionStatus.COMPLETED) {
                                completedMoves++
                                logicalBytesMoved += item.file.sizeBytes
                                batchCompleted++
                                batchBytes += item.file.sizeBytes
                                if (batchCompleted % STATISTICS_BATCH_SIZE == 0L) checkpoint(finished = false)
                            }
                        }
                    }
                    results[index] = result
                    remainingMoves--
                    if (result.status == FileMoveExecutionStatus.FAILED) {
                        failedMoves++
                        rejectGroups(item.groupKeys, result.error ?: "Group execution failed")
                    }
                    if (result.status == FileMoveExecutionStatus.UNCERTAIN) uncertainMoves++
                    val stop = result.status == FileMoveExecutionStatus.UNCERTAIN || result.postProcessingError != null
                    onProgress(FileExecutionProgress(
                        total = results.size,
                        completed = completedMoves.toInt(),
                        failed = failedMoves,
                        uncertain = uncertainMoves,
                        currentSource = item.move.sourcePath,
                        finished = stop || remainingMoves == 0,
                        logicalBytesMoved = logicalBytesMoved,
                    ))
                    currentCoroutineContext().ensureActive()
                    if (stop) break@batches
                }
                withContext(NonCancellable) { checkpoint(finished = true) }
                statisticsReady = false
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
        FileExecutionResult(proposal.id, resultItems,
            statisticsErrors.entries.joinToString("\n") { (id, detail) -> "$id: $detail" }.ifEmpty { null })
    }

    private suspend fun executeItem(
        item: Item, binding: ArchiveBinding, journal: Journal,
        beforeMove: suspend (ProposedFileMove) -> Unit, recordMove: suspend (FileRecord) -> Unit,
    ): FileMoveExecutionResult {
        val move = item.move
        val attempt = historyId(nextId())
        var intentFlushed = false
        var intentAttempted = false
        var renameInvoked = false
        var moveReturned = false
        var completed = false
        try {
            beforeMove(move)
            filesystemLane.run("move reviewed file with flushed journal") {
                try {
                    verifyBinding(binding)
                } catch (failure: Exception) {
                    throw FileOperationException(FileOperationFinding(FileOperationIssueCode.CONFIGURATION_ERROR,
                        detail = failure.message), global = true)
                }
                preflight(item, binding)
                intentAttempted = true
                journal.append(item.id, attempt, "INTENT")
                intentFlushed = true
                renameInvoked = true
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
            if (failure is FileOperationException && failure.global) throw failure
            // A thrown rename is never promoted to success, even if a destination
            // with matching size happens to exist. Only proven no-effect is FAILED.
            var detail = if (failure is FileOperationException) findingsText(listOf(failure.finding))
                else failure.message ?: failure.toString()
            failure.suppressedExceptions.forEach { detail += "; journal rollback failed: ${it.message}" }
            val noEffect = if (!renameInvoked || failure is FileMoveRejectedException) true else try {
                filesystemLane.run("verify failed move had no effect") {
                    safeDirectory(requireNotNull(move.sourcePath.parent), required = true)
                    safeDirectory(requireNotNull(move.destinationPath?.parent), required = false)
                    fileSystem.metadataOrNull(move.sourcePath)?.let { it.isRegularFile && it.symlinkTarget == null } == true &&
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
                if (failed) FileMoveExecutionStatus.FAILED else FileMoveExecutionStatus.UNCERTAIN, detail,
                postProcessingError = if (intentAttempted && !intentFlushed) "Intent journal was not flushed; reconciliation required" else null)
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

    private fun findingsText(findings: List<FileOperationFinding>): String =
        findings.joinToString("; ") { "${it.code}: ${it.detail ?: it.path ?: "review rejected"}" }

    /** Pure snapshot/path validation. Candidate filesystem paths are deliberately not inspected. */
    private fun review(proposal: FileOperationProposal, binding: ArchiveBinding): FileOperationProposal {
        historyId(proposal.id)
        require(proposal.findings.isEmpty()) { "Global proposal validation failed: ${findingsText(proposal.findings)}" }
        require(proposal.binRoot == binding.binRoot) { "The Bin differs from the approved proposal" }
        require(proposal.archiveRoot == null || proposal.archiveRoot == binding.archiveRoot) { "Archive root differs from proposal" }
        require(binding.archiveRoot.isAbsolute && binding.archiveRoot == binding.archiveRoot.normalized() &&
            binding.binRoot.isAbsolute && binding.binRoot == binding.binRoot.normalized() &&
            binding.archiveRoot.parent != null && binding.binRoot.parent != null &&
            !binding.archiveRoot.isInTree(binding.binRoot)) { "Unsafe archive/Bin roots" }
        historyId(binding.identity.archiveId)
        historyId(binding.identity.registrationToken)
        val errors = mutableMapOf<Path, MutableList<FileOperationFinding>>()
        fun reject(path: Path, detail: String) {
            errors.getOrPut(path) { mutableListOf() } +=
                FileOperationFinding(FileOperationIssueCode.UNSAFE_PATH, path, detail)
        }
        val members = proposal.groups.flatMap { group -> group.members.map { group to it.file } }.groupBy { it.second.path }
        val groupsByKey = proposal.groups.groupBy { it.key }
        val sources = proposal.moves.groupBy { it.sourcePath }
        val foldedSources = sources.keys.groupBy { it.toString().lowercase() }
        for ((path, entries) in members) {
            if (entries.map { it.second }.distinct().size > 1)
                reject(path, "Conflicting reviewed file snapshots")
        }
        for ((path, moves) in sources) {
            if (moves.size > 1 || foldedSources.getValue(path.toString().lowercase()).size > 1)
                reject(path, "Duplicate or case-colliding source")
        }
        for (group in proposal.groups) {
            val detail = when {
                groupsByKey.getValue(group.key).size > 1 -> "Duplicate reviewed group keys"
                group.members.any { it.classification == DirectoryClassification.PROTECTED } -> "Protected reviewed group"
                else -> null
            }
            if (detail != null) group.members.filter { it.file.path in sources }.forEach { reject(it.file.path, detail) }
        }
        val eligible = proposal.executionEligibility.moves.toSet()
        for (move in proposal.moves) {
            if (move !in eligible) continue
            try {
                val survivor = requireNotNull(move.survivorPath) { "Missing retained replacement" }
                for (path in listOf(move.sourcePath, survivor)) {
                    archiveRelativePath(path, binding.archiveRoot)
                    require(!path.isInTree(binding.binRoot) && !path.isHistoryMetadata() && !path.isBinMarkerMetadata()) {
                        "Reviewed path targets Bin or reserved metadata"
                    }
                    require(members[path] != null) { "Reviewed snapshot is missing: $path" }
                }
                if (survivor.toString().lowercase() in foldedSources) {
                    foldedSources.getValue(survivor.toString().lowercase()).forEach {
                        reject(it, "Retained replacement is also selected for movement")
                    }
                    error("Retained replacement is also selected for movement")
                }
                val destination = requireNotNull(operationDestinationPath(move.sourcePath, binding.binRoot))
                require(move.destinationPath == destination && move.relativePath == destination.relativeTo(binding.binRoot)) {
                    "Unsafe or changed destination mapping"
                }
                archiveRelativePath(destination, binding.binRoot)
                val file = members.getValue(move.sourcePath).first().second
                require(file.sizeBytes >= 0 && file.sizeBytes == move.sizeBytes) { "Reviewed file size mismatch" }
            } catch (failure: Exception) {
                reject(move.sourcePath, failure.message ?: failure.toString())
            }
        }
        // Attribute cross-item collisions to every involved group, not to the run.
        fun paths(entries: List<Pair<Path, Path>>, duplicates: Boolean) {
            val byPath = entries.groupBy { it.first.toString().replace('\\', '/') }
            val prefixes = mutableMapOf<String, Pair<String, MutableSet<Path>>>()
            val foldedFiles = byPath.keys.groupBy { it.lowercase() }
            for ((path, references) in byPath) {
                val owners = references.map { it.second }.toSet()
                if (duplicates && references.size > 1 || foldedFiles.getValue(path.lowercase()).size > 1) {
                    foldedFiles.getValue(path.lowercase()).flatMap { byPath.getValue(it) }.forEach {
                        reject(it.second, "Duplicate or case-colliding path: $path")
                    }
                }
                var prefix = ""
                for ((index, segment) in path.split('/').withIndex()) {
                    prefix = if (index == 0) segment else "$prefix/$segment"
                    val previous = prefixes[prefix.lowercase()]
                    if (previous != null && previous.first != prefix) {
                        (owners + previous.second).forEach { reject(it, "Case-colliding path segment: $prefix") }
                    }
                    val entry = prefixes.getOrPut(prefix.lowercase()) { prefix to mutableSetOf() }
                    entry.second.addAll(owners)
                    if (prefix != path) {
                        foldedFiles[prefix.lowercase()].orEmpty().forEach { ancestor ->
                            (owners + byPath.getValue(ancestor).map { it.second }).forEach {
                                reject(it, "File/directory path collision: $prefix")
                            }
                        }
                    }
                }
            }
        }
        val candidates = proposal.moves.filter { it in eligible && it.sourcePath !in errors }
        paths(candidates.flatMap { move ->
            listOf(move.sourcePath to move.sourcePath, requireNotNull(move.survivorPath) to move.sourcePath)
        }, duplicates = false)
        paths(candidates.map { requireNotNull(it.destinationPath) to it.sourcePath }, duplicates = true)
        val groups = proposal.groups.associateBy { it.key }
        val groupSizes = mutableMapOf<String, Long>()
        candidates.forEachIndexed { index, move ->
            if (move.sourcePath in errors) return@forEachIndexed
            val entries = members.getValue(move.sourcePath)
            val keys = entries.map { it.first.key }.distinct()
            val item = Item("item-${index + 1}", move, entries.first().second,
                members.getValue(requireNotNull(move.survivorPath)).first().second, entries.first().first, keys)
            val payload = itemManifestText(item, binding).encodeUtf8().size.toLong() + 1 +
                keys.sumOf { key -> groupSizes.getOrPut(key) {
                    groupManifestText(groups.getValue(key)).encodeUtf8().size.toLong() + 1
                } }
            if (payload > BATCH_PAYLOAD_LIMIT)
                reject(move.sourcePath, "Individual reviewed item/group exceeds history reader limit")
        }
        // A conflict in a retained snapshot also invalidates all groups depending on it.
        val groupErrors = proposal.groups.associate { group ->
            group.key to group.members.flatMap { errors[it.file.path].orEmpty() }
        }
        return proposal.copy(
            groups = proposal.groups.map { it.copy(findings = it.findings + groupErrors[it.key].orEmpty()) },
            moves = proposal.moves.map { it.copy(findings = it.findings + errors[it.sourcePath].orEmpty()) },
        )
    }

    private fun plan(reviewed: FileOperationProposal, binding: ArchiveBinding): List<Plan> {
        val proposal = reviewed.copy(moves = reviewed.executionEligibility.moves)
        if (proposal.moves.isEmpty()) return emptyList()
        val files = proposal.groups.flatMap { group -> group.members.map { group to it.file } }.groupBy { it.second.path }
        val snapshots = files.mapValues { (_, entries) ->
            entries.first()
        }
        val sourceGroups = files.mapValues { (_, entries) -> entries.map { it.first.key }.distinct() }
        fun snapshot(path: Path): Pair<FileOperationGroup, FileRecord> =
            requireNotNull(snapshots[path]) { "Reviewed file snapshot is missing: $path" }
        val sources = proposal.moves.map { it.sourcePath }.toSet()
        require(sources.size == proposal.moves.size) { "Duplicate sources" }
        val foldedSources = sources.map { it.toString().lowercase() }.toSet()
        require(foldedSources.size == sources.size) { "Case-colliding sources" }
        val items = proposal.moves.mapIndexed { index, move ->
            val survivorPath = requireNotNull(move.survivorPath)
            for (path in listOf(move.sourcePath, survivorPath)) {
                archiveRelativePath(path, binding.archiveRoot)
                require(!path.isInTree(binding.binRoot) && !path.isHistoryMetadata() && !path.isBinMarkerMetadata()) {
                    "Reviewed path targets Bin or reserved metadata"
                }
            }
            require(survivorPath.toString().lowercase() !in foldedSources) { "Retained replacement is also selected for movement" }
            val destination = requireNotNull(operationDestinationPath(move.sourcePath, binding.binRoot))
            require(move.destinationPath == destination && move.relativePath == destination.relativeTo(binding.binRoot)) {
                "Unsafe or changed destination mapping"
            }
            val (group, file) = snapshot(move.sourcePath)
            require(file.sizeBytes >= 0 && file.sizeBytes == move.sizeBytes)
            Item("item-${index + 1}", move, file, snapshot(survivorPath).second, group,
                sourceGroups.getValue(move.sourcePath))
        }
        validateExecutionPaths(items.flatMap { listOf(it.file.path.toString(), it.survivor.path.toString()) }.distinct())
        validateExecutionPaths(items.map { requireNotNull(it.move.destinationPath).toString() })
        // Check the aggregate byte count once, before any move can overflow progress.
        proposal.candidateBytes
        val groupsByKey = proposal.groups.associateBy { it.key }
        val groupJson = mutableMapOf<String, String>()
        fun groupText(key: String) = groupJson.getOrPut(key) {
            groupManifestText(groupsByKey.getValue(key))
        }
        fun header(id: String, batchIndex: Int?, batchCount: Int?) = buildJsonObject {
            put("formatVersion", 1)
            put("operationId", id)
            put("archiveId", binding.identity.archiveId)
            put("createdAt", timestamp(proposal.createdAt))
            if (batchIndex != null) {
                put("approvedProposalId", proposal.id)
                put("batchIndex", batchIndex)
                put("batchCount", requireNotNull(batchCount))
            }
        }.toString().dropLast(1)
        // Encode each item/group once. Incremental byte accounting avoids repeatedly
        // serializing a growing batch (or scanning all approved groups for each batch).
        val texts = items.map { itemManifestText(it, binding) }
        val referenced = items.map { it.groupKeys }
        val itemBytes = texts.map { it.encodeUtf8().size.toLong() }
        val groupBytes = mutableMapOf<String, Long>()
        fun bytes(key: String) = groupBytes.getOrPut(key) { groupText(key).encodeUtf8().size.toLong() }
        fun manifest(id: String, from: Int, to: Int, keys: Set<String>, index: Int?, count: Int?) =
            header(id, index, count) + ",\"groups\":[" + keys.joinToString(",", transform = ::groupText) +
                "],\"items\":[" + texts.subList(from, to).joinToString(",") + "]}"
        val allKeys = referenced.flatten().toSet()
        val smallBytes = header(proposal.id, null, null).encodeUtf8().size.toLong() + 26 +
            itemBytes.sum() + items.size + allKeys.sumOf(::bytes) + allKeys.size
        val ranges = mutableListOf<Triple<Int, Int, Set<String>>>()
        if (items.size <= MAX_BATCH_MOVES && smallBytes <= MANIFEST_LIMIT) {
            ranges += Triple(0, items.size, allKeys)
        } else {
            // Reserve room for the bounded JSON envelope before filling each batch.
            var from = 0
            var keys = linkedSetOf<String>()
            var size = 0L
            for (index in items.indices) {
                val added = referenced[index].filter { it !in keys }
                val extra = itemBytes[index] + 1 + added.sumOf { bytes(it) + 1 }
                if (index > from && (index - from == MAX_BATCH_MOVES || size + extra > BATCH_PAYLOAD_LIMIT)) {
                    ranges += Triple(from, index, keys)
                    from = index
                    keys = linkedSetOf()
                    size = 0L
                }
                val newKeys = referenced[index].filter { it !in keys }
                val actualExtra = itemBytes[index] + 1 + newKeys.sumOf { bytes(it) + 1 }
                require(size + actualExtra <= BATCH_PAYLOAD_LIMIT) { "Individual reviewed item/group exceeds history reader limit" }
                keys.addAll(newKeys)
                size += actualExtra
            }
            ranges += Triple(from, items.size, keys)
        }
        val batched = ranges.size > 1 || items.size > MAX_BATCH_MOVES || smallBytes > MANIFEST_LIMIT
        val longestSuffix = "-batch-${ranges.size.toString().padStart(3, '0')}"
        val batchPrefix = if (proposal.id.length + longestSuffix.length <= 128) proposal.id else Uuid.random().toString()
        return ranges.mapIndexed { index, (from, to, keys) ->
            val id = if (!batched) proposal.id else historyId("$batchPrefix-batch-${(index + 1).toString().padStart(3, '0')}")
            val text = manifest(id, from, to, keys, if (batched) index + 1 else null,
                if (batched) ranges.size else null)
            require(text.encodeUtf8().size <= MANIFEST_LIMIT) { "Manifest exceeds history reader limit" }
            parseHistoryOperation(id, binding.identity.archiveId, text, "")
            require((to - from).toLong() * (2 * 2048L + ERROR_LIMIT * 6L) <= JOURNAL_LIMIT) {
                "Execution journal could exceed history reader limit"
            }
            Plan(binding.binRoot / "$HISTORY_DIRECTORY_NAME/operations/$id", text, items.subList(from, to))
        }
    }

    private fun groupManifestText(group: FileOperationGroup) = buildJsonObject {
        put("key", group.key)
        put("categoryId", group.categoryId)
        put("categoryName", group.categoryName)
        put("explanation", group.explanation)
        put("duplicateConfidence", group.duplicateConfidence)
    }.toString()

    private fun itemManifestText(item: Item, binding: ArchiveBinding) = buildJsonObject {
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
    }.toString()

    private fun validateExecutionPaths(paths: List<String>) {
        val prefixes = mutableMapOf<String, String>()
        val folded = mutableSetOf<String>()
        for (rawPath in paths) {
            val path = rawPath.replace('\\', '/')
            require(folded.add(path.lowercase())) { "Duplicate or case-colliding path" }
            var prefix = ""
            for ((index, segment) in path.split('/').withIndex()) {
                prefix = if (index == 0) segment else "$prefix/$segment"
                val previous = prefixes.put(prefix.lowercase(), prefix)
                require(previous == null || previous == prefix) { "Case-colliding path segment" }
            }
        }
        require(folded.sorted().zipWithNext().none { (file, next) -> next.startsWith("$file/") }) {
            "File/directory path collision"
        }
    }

    private fun preflight(item: Item, binding: ArchiveBinding) {
        for (file in listOf(item.survivor)) {
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
        verifyDestinationMarkers(item, binding)
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
        const val MAX_BATCH_MOVES = 500
        // Covers the bounded IDs, timestamp, provenance and JSON envelope.
        const val BATCH_PAYLOAD_LIMIT = MANIFEST_LIMIT - 2048
    }
}
