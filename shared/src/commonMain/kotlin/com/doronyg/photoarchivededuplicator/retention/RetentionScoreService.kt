package com.doronyg.photoarchivededuplicator.retention

import com.doronyg.photoarchivededuplicator.Diagnostics
import com.doronyg.photoarchivededuplicator.LogTags
import com.doronyg.photoarchivededuplicator.scan.DirectoryRepository
import com.doronyg.photoarchivededuplicator.scan.DuplicateCandidateGroup
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import com.doronyg.photoarchivededuplicator.scan.SuspectedDuplicateGroup
import com.doronyg.photoarchivededuplicator.exif.ExifInput
import com.doronyg.photoarchivededuplicator.exif.ExifRecord
import com.doronyg.photoarchivededuplicator.exif.ExifStatus
import com.doronyg.photoarchivededuplicator.exif.KIM_VERSION
import com.doronyg.photoarchivededuplicator.exif.isValidExifOffset
import com.doronyg.photoarchivededuplicator.exif.validatedExifTimestamp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Path
import kotlin.time.Clock

data class RetentionAssessmentResult(
    val files: Map<Path, RetentionPresentation>,
    val invalidatedFiles: Int,
)

/** Separate from scanning and candidate detection; consumes only stored metadata snapshots. */
class RetentionScoreService internal constructor(
    private val repository: DirectoryRepository,
    private val scorer: RetentionScorer = RetentionScorer(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val assessment = Mutex()
    private val barrier = MutableStateFlow(AssessmentBarrier())

    /** Overlapping assessments read stored results; call final assess after this block returns. */
    internal suspend fun <T> withoutAssessments(block: suspend () -> T): T = withContext(dispatcher) {
        assessment.withLock {
            val generation = barrier.value.generation + 1
            barrier.value = AssessmentBarrier(generation, extracting = true)
            try {
                block()
            } finally {
                barrier.value = AssessmentBarrier(generation)
            }
        }
    }

    /** Reads persisted scores only, so browsing cannot reassess a partially processed EXIF queue. */
    suspend fun stored(groups: List<DuplicateCandidateGroup>): RetentionAssessmentResult = withContext(dispatcher) {
        readStored(groups.map { it.files })
    }

    suspend fun storedSuspected(groups: List<SuspectedDuplicateGroup>): RetentionAssessmentResult = withContext(dispatcher) {
        readStored(groups.map { it.files }, groups)
    }

    private suspend fun readStored(
        groups: List<List<FileRecord>>,
        suspected: List<SuspectedDuplicateGroup>? = null,
    ): RetentionAssessmentResult {
        val context = currentCoroutineContext()
        val files = groups.flatten()
        val paths = files.map { it.path }
        val scores = repository.fileScores(paths, scorer.identity, currentOnly = true).associateBy { it.input.path }
        val exif = repository.fileExif(paths, currentOnly = true).associateBy { it.input.path }
        val invalid = invalidSuspectedInputs(suspected, exif)
        val current = buildMap {
            for (file in files) {
                context.ensureActive()
                val score = scores[file.path]
                if (file.path !in invalid && score != null && score.input == FileScoreInput.from(file) &&
                    scorer.hasCurrentExifEvidence(score, file, exif[file.path])) put(file.path, score)
            }
        }
        return RetentionAssessmentResult(present(groups, current), invalid.size)
    }

    suspend fun assess(
        groups: List<DuplicateCandidateGroup>,
        force: Boolean = false,
    ): RetentionAssessmentResult = assessGroups({ groups.map { it.files } }, force)

    suspend fun assessSuspected(
        groups: List<SuspectedDuplicateGroup>,
        force: Boolean = false,
    ): RetentionAssessmentResult = assessGroups({ groups.map { it.files } }, force, groups)

    private suspend fun assessGroups(
        loadGroups: () -> List<List<FileRecord>>,
        force: Boolean,
        suspected: List<SuspectedDuplicateGroup>? = null,
    ): RetentionAssessmentResult {
        val requestedAt = barrier.value
        return withContext(dispatcher) {
            val groups = loadGroups()
            Diagnostics.logger.operation(LogTags.RETENTION, "assess groups=${groups.size} force=$force") {
                assessment.withLock {
                    if (requestedAt.extracting || requestedAt.generation != barrier.value.generation) {
                        return@withLock readStored(groups, suspected)
                    }
                    val context = currentCoroutineContext()
                    val files = groups.flatten()
                    val paths = files.map { it.path }
                    val scores = repository.fileScores(paths, scorer.identity, currentOnly = true)
                        .associateBy { it.input.path }.toMutableMap()
                    val exif = repository.fileExif(paths, currentOnly = true).associateBy { it.input.path }
                    val invalid = invalidSuspectedInputs(suspected, exif)
                    invalid.forEach { scores.remove(it) }
                    var invalidated = invalid.size
                    for (chunk in files.chunked(SCORE_WRITE_BATCH_SIZE)) {
                        context.ensureActive()
                        val computed = chunk.mapNotNull { file ->
                            context.ensureActive()
                            if (file.path in invalid) return@mapNotNull null
                            val saved = scores[file.path]
                            val storedExif = exif[file.path]
                            if (!force && saved?.input == FileScoreInput.from(file) &&
                                scorer.hasCurrentExifEvidence(saved, file, storedExif)) null
                            else scorer.score(file, clock(), storedExif)
                        }
                        if (computed.isNotEmpty()) {
                            val accepted = repository.saveFileScores(computed,
                                expectedExif = computed.associate { it.input.path to exif[it.input.path] })
                            computed.forEach { score ->
                                if (score.input.path in accepted) scores[score.input.path] = score
                                else {
                                    scores.remove(score.input.path)
                                    invalidated++
                                }
                            }
                        }
                    }
                    RetentionAssessmentResult(present(groups, scores), invalidated)
                }
            }
        }
    }

    private suspend fun present(
        groups: List<List<FileRecord>>,
        scores: Map<Path, FileScore>,
    ): Map<Path, RetentionPresentation> = buildMap {
        val context = currentCoroutineContext()
        for (group in groups) {
            context.ensureActive()
            putAll(presentRetentionFiles(group, scores, scorer.identity, scorer.ambiguityMargin))
        }
    }

    private suspend fun invalidSuspectedInputs(
        groups: List<SuspectedDuplicateGroup>?,
        exif: Map<Path, ExifRecord>,
    ): Set<Path> = buildSet {
        val context = currentCoroutineContext()
        for (group in groups.orEmpty()) {
            for (file in group.files) {
                context.ensureActive()
                val record = exif[file.path]
                val metadata = record?.result?.metadata
                val offset = metadata?.offsetOriginal?.trim()?.takeIf { it.isNotEmpty() }
                if (record == null || record.input != ExifInput.from(file) ||
                    record.result.status != ExifStatus.EXTRACTED ||
                    record.readerId != "kim" || record.readerVersion != KIM_VERSION ||
                    validatedExifTimestamp(metadata?.capturedAt) != group.capturedAt ||
                    offset != group.offsetOriginal || (offset != null && !isValidExifOffset(offset))) {
                    add(file.path)
                }
            }
        }
    }

    private data class AssessmentBarrier(val generation: Long = 0, val extracting: Boolean = false)

    private companion object {
        const val SCORE_WRITE_BATCH_SIZE = 64
    }
}
