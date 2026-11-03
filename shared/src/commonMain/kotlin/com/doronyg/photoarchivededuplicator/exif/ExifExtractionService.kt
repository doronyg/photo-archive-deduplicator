package com.doronyg.photoarchivededuplicator.exif

import com.doronyg.photoarchivededuplicator.Diagnostics
import com.doronyg.photoarchivededuplicator.LogTags
import com.doronyg.photoarchivededuplicator.scan.DirectoryRepository
import com.doronyg.photoarchivededuplicator.scan.DuplicateCandidateGroup
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Path
import kotlin.time.Clock

/** Reads only changed inputs/library versions; all outcomes, including failures, are cached. */
class ExifExtractionService internal constructor(
    private val repository: DirectoryRepository,
    private val source: ExifSource,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val readerId: String = "kim",
    private val readerVersion: String = KIM_VERSION,
) {
    private val extraction = Mutex()

    suspend fun stored(groups: List<DuplicateCandidateGroup>): Map<Path, ExifRecord> = withContext(dispatcher) {
        val inputs = groups.flatMap { it.files }.associate { it.path to ExifInput.from(it) }
        repository.fileExif(inputs.keys.toList(), currentOnly = true)
            .filter { it.input == inputs[it.input.path] }
            .associateBy { it.input.path }
    }

    suspend fun extract(
        group: DuplicateCandidateGroup,
        onProgress: suspend (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): Map<Path, ExifRecord> = extract(group.files, onProgress)

    suspend fun extract(
        files: List<FileRecord>,
        onProgress: suspend (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): Map<Path, ExifRecord> = withContext(dispatcher) {
        extraction.withLock {
            Diagnostics.logger.operation(LogTags.EXIF, "extract group files=${files.size}") {
                val context = currentCoroutineContext()
                val results = linkedMapOf<Path, ExifRecord>()
                val cached = repository.fileExif(files.map { it.path }, currentOnly = true).associateBy { it.input.path }
                onProgress(0, files.size)
                files.forEachIndexed { index, file ->
                    context.ensureActive()
                    val input = ExifInput.from(file)
                    val current = repository.file(file.path)
                    val matchesInventory = current != null && ExifInput.from(current) == input
                    val saved = cached[file.path]
                    if (matchesInventory && saved?.input == input &&
                        saved.readerId == readerId && saved.readerVersion == readerVersion) {
                        results[file.path] = saved
                        onProgress(index + 1, files.size)
                        return@forEachIndexed
                    }
                    val result = if (!matchesInventory) {
                        staleInventory()
                    } else source.read(file)
                    context.ensureActive()
                    var record = ExifRecord(input, result, clock(), readerId, readerVersion)
                    if (matchesInventory && !repository.saveFileExif(record)) record = record.copy(result = staleInventory())
                    results[file.path] = record
                    if (record.result.status in setOf(ExifStatus.ERROR, ExifStatus.STALE, ExifStatus.LIMIT_EXCEEDED)) {
                        Diagnostics.logger.w(LogTags.EXIF, "${record.result.status}: ${file.path}: ${record.result.error}")
                    }
                    onProgress(index + 1, files.size)
                }
                results
            }
        }
    }

    private fun staleInventory() = ExifReadResult(
        ExifStatus.STALE,
        error = "Inventory changed since this search. Search again before extracting this file.",
    )
}
