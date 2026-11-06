package com.doronyg.photoarchivededuplicator.operations.history

import com.doronyg.photoarchivededuplicator.IoLane
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.FileMetadata
import okio.FileSystem
import okio.Path
import okio.IOException
import okio.buffer
import kotlin.uuid.Uuid

/**
 * Metadata-only registration and browsing; no content mutation or executor API.
 *
 * Exclusive directories serialize cooperating registrars (including marker publication).
 * A leftover lock is an explicit error, never automatically stolen. Publication uses a
 * unique same-directory file, flush/close, then atomicMove while holding these locks.
 * Okio atomicMove itself replaces destinations: this is NOT a general no-overwrite
 * primitive against uncooperative writers. Okio has no portable fsync; neither flush nor
 * a filesystem rename guarantees storage-level crash durability. Identity tokens detect a wrong
 * pairing, not malicious copying of both identifiers or concurrent symlink replacement.
 */
internal class OperationHistoryStore(
    private val filesystemLane: IoLane,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val nextId: () -> String = { Uuid.random().toString() },
) {
    val moveCapabilityEnabled: Boolean = false
    private data class Fingerprint(val size: Long, val modifiedAt: Long)
    private data class Cached(val manifest: Fingerprint, val journal: Fingerprint, val entry: OperationHistoryEntry)
    private val cache = LinkedHashMap<Path, Cached>()

    suspend fun clearCache() = filesystemLane.run("clear operation history cache") { cache.clear() }

    suspend fun register(archiveRoot: Path, binRoot: Path): ArchiveBinding = withContext(dispatcher) {
        filesystemLane.run("register archive metadata") {
            roots(archiveRoot, binRoot)
            val history = binRoot / HISTORY_DIRECTORY_NAME
            // Source first also serializes installations pairing this archive with different Bins.
            val archiveLock = archiveRoot / "$ARCHIVE_MARKER_NAME.lock"
            acquireLock(archiveLock)
            try {
                // Validate before persistent writes, especially when an existing Bin names another archive.
                val existingBin = optionalIdentity(history / "bin.json", bin = true)
                val existingMarker = optionalIdentity(archiveRoot / ARCHIVE_MARKER_NAME)
                if (existingBin != null) require(existingMarker == existingBin) { "Archive marker does not match registered Bin" }
                ensureDirectory(binRoot)
                ensureDirectory(history)
                val binLock = history / ".registration.lock"
                acquireLock(binLock)
                try {
                    val binIdentity = optionalIdentity(history / "bin.json", bin = true)
                    val marker = optionalIdentity(archiveRoot / ARCHIVE_MARKER_NAME)
                    if (binIdentity != null) require(marker == binIdentity) { "Archive marker does not match registered Bin" }
                    val identity = binIdentity ?: marker ?: ArchiveIdentity(newId(), newId())
                    val operations = history / "operations"
                    val existingOperations = checkPath(operations, directory = true)
                    require(binIdentity != null || existingOperations == null || fileSystem.list(operations).isEmpty()) {
                        "Unregistered Bin contains operation history; refusing to replace its identity"
                    }
                    if (marker == null) publish(archiveRoot / ARCHIVE_MARKER_NAME, identityJson(identity))
                    if (binIdentity == null) publish(history / "bin.json", identityJson(identity, newId()))
                    ensureDirectory(operations)
                    ArchiveBinding(identity, archiveRoot, binRoot)
                } finally {
                    fileSystem.delete(binLock, mustExist = true)
                }
            } finally {
                fileSystem.delete(archiveLock, mustExist = true)
            }
        }
    }

    suspend fun verify(archiveRoot: Path, binRoot: Path): ArchiveBinding = withContext(dispatcher) {
        filesystemLane.run("verify archive identity") {
            roots(archiveRoot, binRoot)
            val bin = requireNotNull(optionalIdentity(binRoot / HISTORY_DIRECTORY_NAME / "bin.json", bin = true)) {
                "Bin is not registered"
            }
            require(optionalIdentity(archiveRoot / ARCHIVE_MARKER_NAME) == bin) { "Archive marker does not match registered Bin" }
            ArchiveBinding(bin, archiveRoot, binRoot)
        }
    }

    suspend fun read(binRoot: Path): OperationHistorySnapshot = withContext(dispatcher) {
        filesystemLane.run("read operation history metadata") {
            val problems = mutableListOf<HistoryProblem>()
            var identity: ArchiveIdentity? = null
            var readable = false
            val entries = mutableListOf<OperationHistoryEntry>()
            val observations = mutableMapOf<Path, FileMetadata?>()
            val context = currentCoroutineContext()
            try {
                validateRoot(binRoot)
                checkPath(binRoot, directory = true, required = true, observations = observations)
                val history = binRoot / HISTORY_DIRECTORY_NAME
                val binIdentity = requireNotNull(optionalIdentity(history / "bin.json", bin = true,
                    observations = observations)) { "Bin metadata is missing" }
                identity = binIdentity
                val operations = history / "operations"
                checkPath(operations, directory = true, required = true, observations = observations)
                val listed = fileSystem.list(operations).sortedBy { it.name }
                readable = true
                val retainedCacheKeys = mutableSetOf<Path>()
                for (directory in listed) {
                    context.ensureActive()
                    try {
                        historyId(directory.name)
                        checkPath(directory, directory = true, required = true, observations = observations)
                        retainedCacheKeys += directory
                        val manifest = directory / "manifest.json"
                        val journal = directory / "events.jsonl"
                        val manifestMetadata = checkPath(manifest, directory = false, required = true, observations = observations)!!
                        val journalMetadata = checkPath(journal, directory = false, observations = observations)
                        val mf = fingerprint(manifestMetadata)
                        val jf = journalMetadata?.let(::fingerprint)
                        if (mf == null || jf == null) cache.remove(directory)
                        val cached = cache[directory]?.takeIf {
                            mf != null && jf != null && it.manifest == mf && it.journal == jf &&
                                it.entry.archiveId == binIdentity.archiveId
                        }
                        var entry = cached?.entry ?: parseHistoryOperation(directory.name, binIdentity.archiveId,
                            boundedRead(manifest, MANIFEST_LIMIT),
                            if (journalMetadata == null) "" else boundedRead(journal, JOURNAL_LIMIT),
                            ensureActive = context::ensureActive)
                        if (journalMetadata == null) entry = entry.copy(
                            problems = entry.problems + "Journal is missing", incomplete = true, journalValid = false)
                        if (cached == null && mf != null && jf != null) {
                            cache[directory] = Cached(mf, jf, entry)
                            if (cache.size > CACHE_LIMIT) cache.remove(cache.keys.first())
                        }
                        entries += entry
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        cache.remove(directory)
                        val message = failure.message ?: "Unreadable operation metadata"
                        entries += OperationHistoryEntry(directory.name, "", binIdentity.archiveId, emptyList(), listOf(message), true, false)
                        problems += HistoryProblem(directory.toString(), message)
                    }
                }
                cache.keys.filter { it.parent == operations && it !in retainedCacheKeys }.forEach { cache.remove(it) }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                problems += HistoryProblem(binRoot.toString(), failure.message ?: "Unreadable Bin metadata")
            }
            val statistics = aggregate(entries, problems)
            OperationHistorySnapshot(identity, entries, problems, statistics, readable = readable)
        }
    }

    private fun aggregate(entries: List<OperationHistoryEntry>, problems: MutableList<HistoryProblem>): OperationHistoryStatistics {
        val confirmed = entries.filter { it.journalValid }.flatMap { entry -> entry.items.map { entry.operationId to it } }
        val occupied = confirmed.filter { it.second.confirmedInBin && it.second.restoreStatus != OperationHistoryRestoreStatus.PENDING }
        val conflicting = mutableSetOf<Pair<String, OperationHistoryItem>>()
        val paths = occupied.sortedBy { it.second.binRelativePath.lowercase() }
        // Check shared case-folded prefixes too: "Photos/a" and "photos/b" are unsafe.
        val prefixes = mutableMapOf<String, Pair<String, Pair<String, OperationHistoryItem>>>()
        val prefixOwners = mutableMapOf<String, MutableList<Pair<String, OperationHistoryItem>>>()
        val caseConflicts = mutableSetOf<String>()
        val files = mutableMapOf<String, Pair<String, OperationHistoryItem>>()
        for (pair in paths) {
            var prefix = ""
            for (segment in pair.second.binRelativePath.split('/')) {
                prefix = if (prefix.isEmpty()) segment else "$prefix/$segment"
                files[prefix.lowercase()]?.let { ancestor ->
                    conflicting += ancestor
                    conflicting += pair
                }
                val previous = prefixes[prefix.lowercase()]
                if (previous != null && previous.first != prefix) caseConflicts += prefix.lowercase()
                val owners = prefixOwners.getOrPut(prefix.lowercase()) { mutableListOf() }
                owners += pair
                if (prefix.lowercase() in caseConflicts) conflicting.addAll(owners)
                if (previous != null && (previous.first != prefix ||
                        previous.second.second.binRelativePath.equals(pair.second.binRelativePath, ignoreCase = true))) {
                    conflicting += previous.second
                    conflicting += pair
                } else prefixes[prefix.lowercase()] = prefix to pair
            }
            files[pair.second.binRelativePath.lowercase()] = pair
        }
        if (conflicting.isNotEmpty()) problems += HistoryProblem(HISTORY_DIRECTORY_NAME,
            "Conflicting current Bin paths across operations; ambiguous occupancy excluded")
        return try {
            var moves = 0L
            var movedBytes = 0L
            var restores = 0L
            var bytesInBin = 0L
            for ((_, item) in confirmed) {
                moves = add(moves, item.completedMoves)
                restores = add(restores, item.completedRestores)
                require(item.completedMoves == 0L || item.sizeBytes <= Long.MAX_VALUE / item.completedMoves) { "History statistics overflow" }
                movedBytes = add(movedBytes, item.sizeBytes * item.completedMoves)
            }
            val unambiguous = occupied.filter { it !in conflicting }
            for ((_, item) in unambiguous) bytesInBin = add(bytesInBin, item.sizeBytes)
            OperationHistoryStatistics(moves, movedBytes, unambiguous.size.toLong(), bytesInBin, restores)
        } catch (failure: IllegalArgumentException) {
            problems += HistoryProblem(HISTORY_DIRECTORY_NAME, failure.message ?: "History statistics overflow")
            OperationHistoryStatistics()
        }
    }

    private fun add(a: Long, b: Long): Long {
        require(b >= 0 && a <= Long.MAX_VALUE - b) { "History statistics overflow" }
        return a + b
    }

    private fun roots(archive: Path, bin: Path) {
        validateRoot(archive)
        validateRoot(bin)
        require(generateSequence(archive) { it.parent }.none { it == bin }) { "Archive must not be in or equal to Bin" }
        checkPath(archive, directory = true, required = true)
        checkPath(bin, directory = true)
        checkPath(requireNotNull(bin.parent), directory = true, required = true)
    }

    private fun validateRoot(path: Path) {
        require(path.isAbsolute && path == path.normalized() && path.parent != null) { "Root must be absolute, normalized, and not a filesystem root" }
    }

    /** Inspect every ancestor, never canonicalize a symlink into a trusted root. */
    private fun checkPath(path: Path, directory: Boolean, required: Boolean = false,
        observations: MutableMap<Path, FileMetadata?>? = null): FileMetadata? {
        var result: FileMetadata? = null
        for (part in generateSequence(path) { it.parent }.toList().asReversed()) {
            val metadata = if (observations?.containsKey(part) == true) observations[part]
                else fileSystem.metadataOrNull(part).also { observations?.put(part, it) }
            if (metadata != null) {
                require(metadata.symlinkTarget == null) { "Symlink path is not trusted: $part" }
                require(if (part == path && !directory) metadata.isRegularFile else metadata.isDirectory) {
                    "Unexpected filesystem entry type: $part"
                }
            }
            if (part == path) result = metadata
        }
        require(!required || result != null) { "Metadata path is missing: $path" }
        return result
    }

    private fun ensureDirectory(path: Path) {
        if (checkPath(path, directory = true) == null) fileSystem.createDirectory(path, mustCreate = true)
    }

    private fun acquireLock(path: Path) {
        require(checkPath(path, directory = true) == null) { "Registration lock exists; inspect it manually before retrying: $path" }
        try {
            fileSystem.createDirectory(path, mustCreate = true)
        } catch (failure: IOException) {
            throw IllegalArgumentException("Could not acquire exclusive registration lock: $path", failure)
        }
    }

    private fun optionalIdentity(path: Path, bin: Boolean = false,
        observations: MutableMap<Path, FileMetadata?>? = null): ArchiveIdentity? {
        if (checkPath(path, directory = false, observations = observations) == null) return null
        return parseIdentity(boundedRead(path, IDENTITY_LIMIT), bin)
    }

    private fun newId() = historyId(nextId())

    private fun identityJson(identity: ArchiveIdentity, binId: String? = null) = buildJsonObject {
        put("formatVersion", 1)
        if (binId != null) put("binId", binId)
        put("archiveId", identity.archiveId)
        put("registrationToken", identity.registrationToken)
    }.toString()

    private fun publish(path: Path, text: String) {
        require(checkPath(path, directory = false) == null) { "Metadata already exists: $path" }
        val staging = requireNotNull(path.parent) / "${path.name}.${newId()}.tmp"
        var ownsStaging = false
        try {
            val sink = fileSystem.sink(staging, mustCreate = true)
            ownsStaging = true
            sink.buffer().use { it.writeUtf8(text); it.flush() }
            require(checkPath(path, directory = false) == null) { "Metadata appeared during registration: $path" }
            fileSystem.atomicMove(staging, path)
        } finally {
            if (ownsStaging) fileSystem.delete(staging, mustExist = false)
        }
    }

    private fun boundedRead(path: Path, limit: Long): String = fileSystem.source(path).buffer().use {
        require(!it.request(limit + 1)) { "Metadata exceeds $limit bytes: $path" }
        it.readUtf8()
    }

    private fun fingerprint(metadata: FileMetadata): Fingerprint? {
        val modified = metadata.lastModifiedAtMillis ?: return null
        val size = metadata.size ?: return null
        return Fingerprint(size, modified)
    }

    private companion object {
        const val CACHE_LIMIT = 128
        const val IDENTITY_LIMIT = 64L * 1024
        const val MANIFEST_LIMIT = 8L * 1024 * 1024
        const val JOURNAL_LIMIT = 16L * 1024 * 1024
    }
}
