package com.doronyg.photoarchivededuplicator.retention

import com.doronyg.photoarchivededuplicator.Diagnostics
import com.doronyg.photoarchivededuplicator.LogTags
import com.doronyg.photoarchivededuplicator.scan.DirectoryRepository
import com.doronyg.photoarchivededuplicator.scan.DuplicateCandidateGroup
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
        val context = currentCoroutineContext()
        val files = groups.flatMap { it.files }
        val paths = files.map { it.path }
        val scores = repository.fileScores(paths, scorer.identity, currentOnly = true).associateBy { it.input.path }
        val exif = repository.fileExif(paths, currentOnly = true).associateBy { it.input.path }
        val current = buildMap {
            for (file in files) {
                context.ensureActive()
                val score = scores[file.path]
                if (score != null && score.input == FileScoreInput.from(file) &&
                    scorer.hasCurrentExifEvidence(score, file, exif[file.path])) put(file.path, score)
            }
        }
        RetentionAssessmentResult(present(groups, current), 0)
    }

    suspend fun assess(
        groups: List<DuplicateCandidateGroup>,
        force: Boolean = false,
    ): RetentionAssessmentResult {
        val requestedAt = barrier.value
        return withContext(dispatcher) {
            Diagnostics.logger.operation(LogTags.RETENTION, "assess groups=${groups.size} force=$force") {
                assessment.withLock {
                    if (requestedAt.extracting || requestedAt.generation != barrier.value.generation) {
                        return@withLock stored(groups)
                    }
                    val context = currentCoroutineContext()
                    val files = groups.flatMap { it.files }
                    val paths = files.map { it.path }
                    val scores = repository.fileScores(paths, scorer.identity, currentOnly = true)
                        .associateBy { it.input.path }.toMutableMap()
                    val exif = repository.fileExif(paths, currentOnly = true).associateBy { it.input.path }
                    var invalidated = 0
                    for (chunk in files.chunked(SCORE_WRITE_BATCH_SIZE)) {
                        context.ensureActive()
                        val computed = chunk.mapNotNull { file ->
                            context.ensureActive()
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
        groups: List<DuplicateCandidateGroup>,
        scores: Map<Path, FileScore>,
    ): Map<Path, RetentionPresentation> = buildMap {
        val context = currentCoroutineContext()
        for (group in groups) {
            context.ensureActive()
            putAll(presentRetentionGroup(group, scores, scorer.identity, scorer.ambiguityMargin))
        }
    }

    private data class AssessmentBarrier(val generation: Long = 0, val extracting: Boolean = false)

    private companion object {
        const val SCORE_WRITE_BATCH_SIZE = 64
    }
}
