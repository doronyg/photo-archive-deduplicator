package com.doronyg.photoarchivededuplicator.scan

import com.doronyg.photoarchivededuplicator.LogTags

import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.db.SqlDriver
import com.doronyg.photoarchivededuplicator.Diagnostics
import com.doronyg.photoarchivededuplicator.IoLane
import com.doronyg.photoarchivededuplicator.UiChoices
import com.doronyg.photoarchivededuplicator.encodeUiChoices
import com.doronyg.photoarchivededuplicator.loadUiChoicesInLane
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassification
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationContext
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationRecord
import com.doronyg.photoarchivededuplicator.db.FilesDatabase
import com.doronyg.photoarchivededuplicator.db.DirectoryAiClassification
import com.doronyg.photoarchivededuplicator.db.ScanDirectory
import com.doronyg.photoarchivededuplicator.db.ScanFile
import com.doronyg.photoarchivededuplicator.exif.ExifInput
import com.doronyg.photoarchivededuplicator.exif.ExifMetadata
import com.doronyg.photoarchivededuplicator.exif.ExifReadResult
import com.doronyg.photoarchivededuplicator.exif.ExifRecord
import com.doronyg.photoarchivededuplicator.exif.ExifStatus
import com.doronyg.photoarchivededuplicator.exif.EXIF_SURROUNDING_WHITESPACE
import com.doronyg.photoarchivededuplicator.exif.KIM_VERSION
import com.doronyg.photoarchivededuplicator.exif.isValidExifOffset
import com.doronyg.photoarchivededuplicator.exif.validatedExifTimestamp
import com.doronyg.photoarchivededuplicator.retention.FileScore
import com.doronyg.photoarchivededuplicator.retention.FileScoreInput
import com.doronyg.photoarchivededuplicator.retention.ScoreFactor
import com.doronyg.photoarchivededuplicator.retention.ScoreIdentity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Path
import okio.Path.Companion.toPath

// Keep the schema-7 column without using the removed preferred-roots configuration.
private const val LEGACY_AI_ROOTS_KEY = ""

internal fun createFilesDatabase(driver: SqlDriver): FilesDatabase =
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
    val createdAt: Long? = null,
)

data class DirectoryExclusion(val path: Path, val reason: String?)
data class DuplicateCandidateGroup(val name: String, val sizeBytes: Long, val files: List<FileRecord>)
data class ScanSessionRecord(val root: Path, val activeMillis: Long, val finished: Boolean = false)

internal fun Path.inventoryPath(): Path = normalized().also {
    require(it.isAbsolute) { "Enter an absolute path." }
}

internal fun Path.isInTree(root: Path): Boolean =
    generateSequence(normalized()) { it.parent }.any { it == root.normalized() }

/** A directory plus the total size of the files directly inside it (not recursive). */
data class DirectoryOverview(val directory: DirectoryRecord, val fileBytes: Long)

data class InventorySummary(
    val directoriesByStatus: Map<DirectoryStatus, Long>,
    val fileCount: Long,
    val totalBytes: Long,
) {
    val directoryCount: Long get() = directoriesByStatus.values.sum()
}

/** Result of listing a single directory; children only, not recursive. */
data class DirectoryListing(
    val subdirectories: List<Entry>,
    val files: List<Entry>,
) {
    data class Entry(val path: Path, val sizeBytes: Long, val modifiedAt: Long?, val createdAt: Long? = null)
}

/**
 * Persists scan progress. Paths are stored as Okio path strings.
 *
 * Every operation holds the database lane across suspension and transactions, so callers
 * suspend without blocking their own thread. The container owns the single JDBC thread.
 * Test dispatchers are injected only for virtual-time tests; nested calls use private
 * in-lane helpers rather than reacquiring the non-reentrant lane.
 */
class DirectoryRepository internal constructor(
    private val database: FilesDatabase,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val databaseLane: IoLane = IoLane("database-test", ioDispatcher),
) {
    private val directories = database.scanDirectoryQueries
    private val files = database.scanFileQueries
    private val excluded = database.directoryExclusionQueries
    private val scanSession = Mutex()

    internal suspend fun loadUiChoices(): UiChoices = io("load UI choices") { loadUiChoicesInLane(database) }

    internal suspend fun saveUiChoices(choices: UiChoices) = io("save UI choices transaction") {
        database.transaction {
            database.uiPreferenceQueries.clear()
            encodeUiChoices(choices).forEach { (key, value) ->
                database.uiPreferenceQueries.put(key, value)
            }
        }
    }

    internal suspend fun <T> scanning(block: suspend () -> T): T =
        Diagnostics.logger.operation(LogTags.DATABASE, "scan mutex wait/hold") { scanSession.withLock { block() } }

    suspend fun currentScanSession(): ScanSessionRecord? = io("currentScanSession") { currentScanSessionInLane() }

    private suspend fun currentScanSessionInLane(): ScanSessionRecord? =
        database.scanSessionQueries.currentSession().awaitAsOneOrNull()?.let {
            ScanSessionRecord(it.root.toPath(), it.activeMillis, it.finished != 0L)
        }

    /** Selecting a new scan and queuing its root commit together, even if Pause wins initialization. */
    suspend fun beginScanSession(root: Path): ScanSessionRecord = io("beginScanSession transaction $root") {
        beginScanSessionInLane(root, newScan = true)
    }

    private suspend fun beginScanSessionInLane(root: Path, newScan: Boolean = false): ScanSessionRecord {
        val normalized = root.inventoryPath()
        Diagnostics.logger.operation(LogTags.DATABASE, "transaction beginScanSession $normalized") {
            database.transaction {
                database.scanSessionQueries.clearSelection()
                database.scanSessionQueries.beginSession(normalized.toString())
                // Legacy resume creates only the session; completed roots must not be requeued.
                if (newScan && exclusionsInLane().none { normalized.isInTree(it.path) }) {
                    enqueue(normalized, parent = null, modifiedAt = null)
                }
            }
        }
        return ScanSessionRecord(normalized, 0)
    }

    /** A legacy inventory can resume automatically only when its root is unambiguous. */
    suspend fun resumableScanSession(root: Path? = null): ScanSessionRecord = io("resumableScanSession transaction $root") {
        if (root != null) {
            val normalized = root.inventoryPath()
            check(directoryInLane(normalized) != null) { "This folder has not been scanned. Start a scan first." }
            val saved = database.scanSessionQueries.sessionForRoot(normalized.toString()).awaitAsOneOrNull()
            if (saved == null) return@io beginScanSessionInLane(normalized)
            Diagnostics.logger.operation(LogTags.DATABASE, "transaction selectSession $normalized") {
                database.transaction {
                    database.scanSessionQueries.clearSelection()
                    database.scanSessionQueries.selectSession(normalized.toString())
                }
            }
            return@io ScanSessionRecord(normalized, saved.activeMillis, saved.finished != 0L)
        }
        currentScanSessionInLane() ?: run {
            val records = allDirectoriesInLane()
            val storedRoots = records.filter { it.parentPath == null }.map { it.path }
            // A separately seeded nested root can remain parentless (e.g. an excluded
            // tree). It must not make its containing scan look like two unrelated roots.
            val roots = storedRoots.filter { root -> storedRoots.none { it != root && root.isInTree(it) } }
            val unfinished = records.filter {
                it.status == DirectoryStatus.PENDING || it.status == DirectoryStatus.IN_PROGRESS
            }
            val candidates = roots.filter { root -> unfinished.any { it.path.isInTree(root) } }
                .ifEmpty { roots }
            check(candidates.size == 1) {
                "No unambiguous scan to resume. Enter a specific scanned root and Resume; other inventory is preserved."
            }
            beginScanSessionInLane(candidates.single())
        }
    }

    suspend fun checkpointScanSession(session: ScanSessionRecord) = io("checkpointScanSession ${session.root}") {
        check(currentScanSessionInLane()?.root == session.root) { "The active scan root changed." }
        database.scanSessionQueries.checkpoint(session.activeMillis, if (session.finished) 1 else 0, session.root.toString())
    }

    private suspend fun editExclusions(name: String, block: suspend () -> Unit) {
        Diagnostics.logger.d(LogTags.DATABASE, "BEGIN exclusions mutex tryLock $name")
        check(scanSession.tryLock()) { "Pause the scan before changing exclusions." }
        try {
            io(name) { block() }
        } finally {
            scanSession.unlock()
        }
    }

    suspend fun exclusions(): List<DirectoryExclusion> = io("exclusions") { exclusionsInLane() }

    private suspend fun exclusionsInLane(): List<DirectoryExclusion> =
        excluded.selectAll().awaitAsList().map { DirectoryExclusion(it.path.toPath(), it.reason) }

    suspend fun addExclusion(path: Path, reason: String? = null) = editExclusions("addExclusion $path") {
        excluded.put(path.inventoryPath().toString(), reason?.trim()?.takeIf { it.isNotEmpty() })
    }

    suspend fun removeExclusion(path: Path) = editExclusions("removeExclusion $path") {
        excluded.remove(path.inventoryPath().toString())
    }

    suspend fun isExcluded(path: Path): Boolean = exclusions().any { path.isInTree(it.path) }

    suspend fun duplicateCandidates(
        root: Path? = null,
        filter: DuplicateCandidateFilter = DuplicateCandidateFilter(),
    ): List<DuplicateCandidateGroup> = io("duplicateCandidates $root") {
        files.selectDuplicateCandidates(root?.inventoryPath()?.toString()).awaitAsList()
            .filter { filter.matches(it.name) }
            .map { FileRecord(it.path.toPath(), it.directoryPath.toPath(), it.name, it.extension,
                it.sizeBytes, it.modifiedAt, it.lastScanned, it.createdAt) }
            .groupBy { it.name to it.sizeBytes }
            .map { (key, members) -> DuplicateCandidateGroup(key.first, key.second, members) }
    }

    suspend fun suspectedExifCandidates(root: Path? = null): List<ImageSizeGroup> =
        io("suspectedExifCandidates $root") {
            files.selectSuspectedExifCandidates(
                minSizeBytes = SUSPECTED_IMAGE_MIN_SIZE_BYTES,
                imageExtensions = DuplicateFileType.IMAGES.extensions,
                root = root?.inventoryPath()?.toString(),
            ).awaitAsList().map { it.toRecord() }
                .groupBy { it.sizeBytes }
                .map { (size, members) -> ImageSizeGroup(size, members) }
        }

    /** Persisted evidence only; never opens source files or starts extraction. */
    suspend fun suspectedDuplicates(root: Path? = null): List<SuspectedDuplicateGroup> =
        io("suspectedDuplicates $root") {
            database.fileExifQueries.selectSuspectedMatches(
                minSizeBytes = SUSPECTED_IMAGE_MIN_SIZE_BYTES,
                imageExtensions = DuplicateFileType.IMAGES.extensions,
                root = root?.inventoryPath()?.toString(),
                whitespace = EXIF_SURROUNDING_WHITESPACE,
                status = ExifStatus.EXTRACTED.name,
                readerId = "kim",
                readerVersion = KIM_VERSION,
            ).awaitAsList().mapNotNull { row ->
                val timestamp = validatedExifTimestamp(row.captureTime) ?: return@mapNotNull null
                val offset = row.captureOffset
                if (offset != null && !isValidExifOffset(offset)) return@mapNotNull null
                Triple(row.sizeBytes, timestamp, offset) to FileRecord(
                    row.path.toPath(), row.directoryPath.toPath(), row.name, row.extension,
                    row.sizeBytes, row.modifiedAt, row.lastScanned, row.createdAt,
                )
            }.groupBy({ it.first }, { it.second })
                .mapNotNull { (key, members) ->
                    if (members.map { it.name }.distinct().size < 2) null
                    else SuspectedDuplicateGroup(key.first, key.second, key.third, members)
                }
        }

    /** Queues [root] for scanning, re-queuing it if a previous scan already processed it. */
    suspend fun seedRoot(root: Path) = io("seedRoot transaction $root") {
        val normalized = root.inventoryPath()
        if (exclusionsInLane().none { normalized.isInTree(it.path) }) {
            Diagnostics.logger.operation(LogTags.DATABASE, "transaction seedRoot $normalized") {
                database.transaction { enqueue(normalized, parent = null, modifiedAt = null) }
            }
        }
    }

    /** Returns directories left IN_PROGRESS by an interrupted run to the queue. */
    suspend fun resetInterrupted(root: Path? = null) = io("resetInterrupted $root") {
        directories.replaceStatus(newStatus = DirectoryStatus.PENDING, oldStatus = DirectoryStatus.IN_PROGRESS,
            root = root?.toString())
    }

    suspend fun fetchPendingBatch(limit: Int, root: Path? = null): List<Path> = io("fetchPendingBatch $root limit=$limit") {
        directories.selectBatchByStatus(DirectoryStatus.PENDING, root?.toString(), limit.toLong()).awaitAsList().map { it.toPath() }
    }

    suspend fun pendingScanCount(root: Path? = null): Long = io("pendingScanCount $root") {
        directories.countScannableWithStatus(DirectoryStatus.PENDING, root?.toString()).awaitAsOne()
    }

    suspend fun markInProgress(path: Path) = io("markInProgress $path") {
        directories.updateStatus(DirectoryStatus.IN_PROGRESS, path.toString())
    }

    /**
     * Atomically reconciles stored children of [dir] with a fresh [listing]
     * and marks [dir] COMPLETED.
     */
    suspend fun recordListing(dir: Path, listing: DirectoryListing, scannedAt: Long) = io("recordListing transaction $dir") {
        val exclusions = exclusionsInLane()
        fun protected(path: Path) = exclusions.any { path.isInTree(it.path) }
        if (protected(dir)) return@io
        Diagnostics.logger.operation(LogTags.DATABASE, "transaction recordListing $dir") { database.transaction {
            val dirKey = dir.toString()

            val listedDirs = listing.subdirectories.filterNot { protected(it.path) }.associateBy { it.path.toString() }
            for (stale in directories.selectChildPaths(dirKey).awaitAsList() - listedDirs.keys) {
                deleteSubtree(stale, exclusions)
            }
            for (sub in listedDirs.values) {
                enqueue(sub.path, parent = dir, modifiedAt = sub.modifiedAt)
            }

            val listedFiles = listing.files.filterNot { protected(it.path) }.associateBy { it.path.toString() }
            for (stale in files.selectPathsInDirectory(dirKey).awaitAsList() - listedFiles.keys) {
                if (!protected(stale.toPath())) files.deleteByPath(stale)
            }
            for ((key, file) in listedFiles) {
                files.updateFile(file.sizeBytes, file.modifiedAt, file.createdAt, scannedAt, key)
                files.insertFileIfAbsent(
                    path = key,
                    directoryPath = dirKey,
                    name = file.path.name,
                    extension = extensionOf(file.path.name),
                    sizeBytes = file.sizeBytes,
                    modifiedAt = file.modifiedAt,
                    createdAt = file.createdAt,
                    lastScanned = scannedAt,
                )
            }

            directories.recordSuccess(DirectoryStatus.COMPLETED,
                files.selectPathsInDirectory(dirKey).awaitAsList().size.toLong(), scannedAt, dirKey)
        } }
    }

    /**
     * Records a failed listing attempt and returns the updated record: PENDING while
     * attempts remain, FAILED once [maxAttempts] is reached. Stored children are kept.
     */
    suspend fun recordFailure(path: Path, maxAttempts: Int): DirectoryRecord = io("recordFailure transaction $path") {
        Diagnostics.logger.operation(LogTags.DATABASE, "transaction recordFailure $path") { database.transactionWithResult {
            val key = path.toString()
            val attempts = (directories.selectByPath(key).awaitAsOneOrNull()?.retryCount ?: 0) + 1
            val status = if (attempts >= maxAttempts) DirectoryStatus.FAILED else DirectoryStatus.PENDING
            directories.recordFailure(status, attempts, key)
            directories.selectByPath(key).awaitAsOneOrNull()!!.toRecord()
        } }
    }

    suspend fun countWithStatus(status: DirectoryStatus): Long = io("countWithStatus $status") {
        directories.countWithStatus(status).awaitAsOne()
    }

    /** Subdirectories of [parent], or the scan roots when [parent] is null. */
    suspend fun childOverviews(parent: Path?): List<DirectoryOverview> = io("childOverviews $parent") {
        directories.selectChildOverviews(parent?.toString()).awaitAsList().map {
            DirectoryOverview(
                directory = ScanDirectory(
                    it.path, it.parentPath, it.status, it.retryCount, it.fileCount, it.modifiedAt, it.lastScanned,
                ).toRecord(),
                fileBytes = it.fileBytes,
            )
        }
    }

    suspend fun filesIn(directory: Path): List<FileRecord> = io("filesIn $directory") {
        files.selectInDirectory(directory.toString()).awaitAsList().map { it.toRecord() }
    }

    /** Stored paths in the whole directory tree, or every inventory path when null. */
    suspend fun filePathsUnder(directory: Path? = null): List<String> = io("filePathsUnder $directory") {
        files.selectPathsInTree(directory?.inventoryPath()?.toString()).awaitAsList()
    }

    suspend fun directoriesWithStatus(status: DirectoryStatus): List<DirectoryRecord> = io("directoriesWithStatus $status") {
        directories.selectWithStatus(status).awaitAsList().map { it.toRecord() }
    }

    suspend fun summary(): InventorySummary = io("summary") {
        val totals = files.selectTotals().awaitAsOne()
        InventorySummary(
            directoriesByStatus = directories.countByStatus().awaitAsList().associate { it.status to it.directoryCount },
            fileCount = totals.fileCount,
            totalBytes = totals.totalBytes ?: 0L,
        )
    }

    suspend fun directory(path: Path): DirectoryRecord? = io("directory $path") { directoryInLane(path) }

    private suspend fun directoryInLane(path: Path): DirectoryRecord? =
        directories.selectByPath(path.toString()).awaitAsOneOrNull()?.toRecord()

    suspend fun allDirectories(): List<DirectoryRecord> = io("allDirectories") { allDirectoriesInLane() }

    private suspend fun allDirectoriesInLane(): List<DirectoryRecord> =
        directories.selectAll().awaitAsList().map { it.toRecord() }

    suspend fun file(path: Path): FileRecord? = io("file $path") {
        files.selectByPath(path.toString()).awaitAsOneOrNull()?.toRecord()
    }

    suspend fun allFiles(): List<FileRecord> = io("allFiles") {
        files.selectAll().awaitAsList().map { it.toRecord() }
    }

    suspend fun directoryClassifications(
        paths: List<Path>,
        context: DirectoryClassificationContext,
    ): List<DirectoryClassificationRecord> = io("directoryClassifications count=${paths.size}") {
        paths.map { it.inventoryPath().toString() }.distinct().chunked(400).flatMap { chunk ->
            database.directoryAiClassificationQueries.selectForPaths(
                chunk, context.baseUrl, context.model, context.promptVersion, LEGACY_AI_ROOTS_KEY,
            ).awaitAsList()
        }.map { it.toRecord() }
    }

    suspend fun allDirectoryClassifications(): List<DirectoryClassificationRecord> =
        io("allDirectoryClassifications") {
            database.directoryAiClassificationQueries.selectAll().awaitAsList().map { it.toRecord() }
        }

    private fun DirectoryAiClassification.toRecord() = DirectoryClassificationRecord(
        directoryPath = directoryPath.toPath(),
        originalPath = originalPath,
        classification = DirectoryClassification.valueOf(classification),
        context = DirectoryClassificationContext(serverUrl, model, promptVersion),
        classifiedAt = classifiedAt,
    )

    suspend fun saveDirectoryClassification(record: DirectoryClassificationRecord): Unit =
        io("saveDirectoryClassification transaction") {
            val path = record.directoryPath.inventoryPath()
            require(record.originalPath.toPath().inventoryPath() == path) {
                "The original directory path must identify the classified directory."
            }
            val context = record.context
            database.transaction {
                database.directoryAiClassificationQueries.upsert(
                    directoryPath = path.toString(),
                    serverUrl = context.baseUrl,
                    model = context.model,
                    promptVersion = context.promptVersion,
                    preferredRootsKey = LEGACY_AI_ROOTS_KEY,
                    originalPath = record.originalPath,
                    classification = record.classification.name,
                    classifiedAt = record.classifiedAt,
                )
            }
        }

    suspend fun fileExif(paths: List<Path>, currentOnly: Boolean = true): List<ExifRecord> =
        io("fileExif count=${paths.size}") {
            fileExifInLane(paths, currentOnly)
        }

    private suspend fun fileExifInLane(paths: List<Path>, currentOnly: Boolean): List<ExifRecord> =
        paths.map { it.toString() }.distinct().chunked(400).flatMap { chunk ->
            database.fileExifQueries.selectForPaths(chunk, if (currentOnly) 1L else 0L).awaitAsList()
        }.map { row ->
            ExifRecord(
                input = ExifInput(row.filePath.toPath(), row.directoryPath.toPath(),
                    row.inputSizeBytes, row.inputModifiedAt, row.inputCreatedAt),
                result = ExifReadResult(
                    status = ExifStatus.valueOf(row.status),
                    metadata = ExifMetadata(row.capturedAt, row.offsetOriginal, row.subsecondOriginal,
                        row.make, row.model, row.orientation?.toInt(), row.width?.toInt(), row.height?.toInt()),
                    error = row.error,
                ),
                extractedAt = row.extractedAt,
                readerId = row.readerId,
                readerVersion = row.readerVersion,
            )
        }

    suspend fun saveFileExif(record: ExifRecord): Boolean = io("saveFileExif transaction") {
        database.transactionWithResult {
            val input = record.input
            val current = files.selectByPath(input.path.toString()).awaitAsOneOrNull()?.toRecord()
            if (current == null || ExifInput.from(current) != input) {
                Diagnostics.logger.w(LogTags.DATABASE, "Discarding stale EXIF: ${input.path}")
                return@transactionWithResult false
            }
            val result = record.result
            val metadata = result.metadata
            database.fileExifQueries.upsert(
                filePath = input.path.toString(),
                directoryPath = input.directoryPath.toString(),
                inputSizeBytes = input.sizeBytes,
                inputModifiedAt = input.modifiedAt,
                inputCreatedAt = input.createdAt,
                status = result.status.name,
                capturedAt = metadata.capturedAt,
                offsetOriginal = metadata.offsetOriginal,
                subsecondOriginal = metadata.subsecondOriginal,
                make = metadata.make,
                model = metadata.model,
                orientation = metadata.orientation?.toLong(),
                width = metadata.width?.toLong(),
                height = metadata.height?.toLong(),
                error = result.error,
                extractedAt = record.extractedAt,
                readerId = record.readerId,
                readerVersion = record.readerVersion,
            )
            true
        }
    }

    suspend fun fileScores(
        paths: List<Path>,
        identity: ScoreIdentity? = null,
        scoreType: String? = null,
        algorithmVersion: String? = null,
        currentOnly: Boolean = false,
    ): List<FileScore> =
        io("fileScores count=${paths.size}") {
            require(identity == null || scoreType == null || identity.type == scoreType) {
                "Score type contradicts the supplied identity."
            }
            require(identity == null || algorithmVersion == null || identity.algorithmVersion == algorithmVersion) {
                "Algorithm version contradicts the supplied identity."
            }
            val queries = database.fileScoreQueries
            val rows = paths.map { it.toString() }.distinct().chunked(400).flatMap { chunk ->
                if (identity == null) {
                    queries.selectForPaths(paths = chunk, scoreType = scoreType, algorithmVersion = algorithmVersion,
                        currentOnly = if (currentOnly) 1L else 0L)
                        .awaitAsList()
                } else {
                    queries.selectForPathsAndIdentity(
                        paths = chunk,
                        scoreType = identity.type,
                        algorithmId = identity.algorithmId,
                        algorithmVersion = identity.algorithmVersion,
                        configurationId = identity.configurationId,
                        currentOnly = if (currentOnly) 1L else 0L,
                    ).awaitAsList()
                }
            }
            val factors = rows.map { it.id }.chunked(400).flatMap {
                database.fileScoreFactorQueries.selectForScores(it).awaitAsList()
            }.groupBy { it.scoreId }
            rows.map { row ->
                FileScore(
                    input = FileScoreInput(row.filePath.toPath(), row.directoryPath.toPath(),
                        row.inputSizeBytes, row.inputModifiedAt, row.inputCreatedAt),
                    identity = ScoreIdentity(row.scoreType, row.algorithmId, row.algorithmVersion, row.configurationId),
                    value = row.value_,
                    factors = factors[row.id].orEmpty().map {
                        ScoreFactor(it.factorKey, it.contribution, it.explanation)
                    },
                    createdAt = row.createdAt,
                )
            }
        }

    suspend fun saveFileScore(score: FileScore): Boolean =
        score.input.path in saveFileScores(listOf(score))

    suspend fun saveFileScores(
        scores: List<FileScore>,
        expectedExif: Map<Path, ExifRecord?>? = null,
    ): Set<Path> = io("saveFileScores transaction count=${scores.size}") {
        scores.forEach { score ->
            require(score.value.isFinite()) { "File score must be finite." }
            require(score.factors.all { it.contribution.isFinite() }) { "Score contributions must be finite." }
            require(expectedExif == null || score.input.path in expectedExif) { "Missing expected EXIF snapshot." }
        }
        if (scores.isEmpty()) return@io emptySet()
        database.transactionWithResult {
            val currentExif = expectedExif?.let {
                fileExifInLane(scores.map { it.input.path }, currentOnly = true).associateBy { it.input.path }
            }
            buildSet {
                for (score in scores) {
                    if (expectedExif != null && currentExif?.get(score.input.path) != expectedExif[score.input.path]) {
                        Diagnostics.logger.w(LogTags.DATABASE, "Discarding score after EXIF changed: ${score.input.path}")
                        continue
                    }
                    if (saveFileScoreInLane(score)) add(score.input.path)
                }
            }
        }
    }

    private suspend fun saveFileScoreInLane(score: FileScore): Boolean {
        val input = score.input
        val current = files.selectByPath(input.path.toString()).awaitAsOneOrNull()?.toRecord()
        if (current == null || FileScoreInput.from(current) != input) {
            Diagnostics.logger.w(LogTags.DATABASE, "Discarding stale file score: ${input.path}")
            return false
        }
        val queries = database.fileScoreQueries
        val identity = score.identity
        queries.insertIfAbsent(
            filePath = input.path.toString(),
            directoryPath = input.directoryPath.toString(),
            inputSizeBytes = input.sizeBytes,
            inputModifiedAt = input.modifiedAt,
            inputCreatedAt = input.createdAt,
            scoreType = identity.type,
            algorithmId = identity.algorithmId,
            algorithmVersion = identity.algorithmVersion,
            configurationId = identity.configurationId,
            value = score.value,
            createdAt = score.createdAt,
        )
        val id = queries.selectIdentityId(
            filePath = input.path.toString(),
            scoreType = identity.type,
            algorithmId = identity.algorithmId,
            algorithmVersion = identity.algorithmVersion,
            configurationId = identity.configurationId,
        ).awaitAsOne()
        queries.updateScore(
            directoryPath = input.directoryPath.toString(),
            inputSizeBytes = input.sizeBytes,
            inputModifiedAt = input.modifiedAt,
            inputCreatedAt = input.createdAt,
            value = score.value,
            createdAt = score.createdAt,
            id = id,
        )
        database.fileScoreFactorQueries.deleteForScore(id)
        score.factors.forEachIndexed { index, factor ->
            database.fileScoreFactorQueries.insertFactor(
                scoreId = id,
                ordinal = index.toLong(),
                factorKey = factor.key,
                contribution = factor.contribution,
                explanation = factor.explanation,
            )
        }
        return true
    }

    private suspend fun <T> io(name: String, block: suspend () -> T): T =
        databaseLane.run(name, block)

    // Private helpers run inside an io { } transaction; they must not switch context themselves.
    private suspend fun enqueue(path: Path, parent: Path?, modifiedAt: Long?) {
        val key = path.toString()
        val parentKey = parent?.toString()
        directories.insertIfAbsent(key, parentKey, DirectoryStatus.PENDING, modifiedAt)
        directories.requeue(DirectoryStatus.PENDING, parentKey, modifiedAt, key)
    }

    private suspend fun deleteSubtree(rootKey: String, exclusions: List<DirectoryExclusion>) {
        for (key in directories.selectSubtree(rootKey).awaitAsList()) {
            val path = key.toPath()
            if (exclusions.any { path.isInTree(it.path) }) continue
            for (file in files.selectPathsInDirectory(key).awaitAsList()) {
                if (exclusions.none { file.toPath().isInTree(it.path) }) files.deleteByPath(file)
            }
            // Keep ancestors so retained protected inventory stays reachable in Browse.
            if (exclusions.none { it.path.isInTree(path) }) {
                directories.deleteByPath(key)
            } else {
                directories.updateFileCount(files.selectPathsInDirectory(key).awaitAsList().size.toLong(), key)
            }
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
        createdAt = createdAt,
    )
}

/** Lower-cased text after the last dot; null for no extension or dot-files like ".hidden". */
internal fun extensionOf(fileName: String): String? {
    val dot = fileName.lastIndexOf('.')
    return if (dot <= 0 || dot == fileName.lastIndex) null else fileName.substring(dot + 1).lowercase()
}
