package com.doronyg.photoarchivededuplicator.retention

import com.doronyg.photoarchivededuplicator.Diagnostics
import com.doronyg.photoarchivededuplicator.LogTags
import com.doronyg.photoarchivededuplicator.scan.DirectoryRepository
import com.doronyg.photoarchivededuplicator.scan.DuplicateCandidateGroup
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

    suspend fun assess(
        groups: List<DuplicateCandidateGroup>,
        force: Boolean = false,
    ): RetentionAssessmentResult = withContext(dispatcher) {
        Diagnostics.logger.operation(LogTags.RETENTION, "assess groups=${groups.size} force=$force") {
            assessment.withLock {
                val context = currentCoroutineContext()
                val files = groups.flatMap { it.files }
                val scores = repository.fileScores(files.map { it.path }, scorer.identity, currentOnly = true)
                    .associateBy { it.input.path }.toMutableMap()
                var invalidated = 0
                for (chunk in files.chunked(SCORE_WRITE_BATCH_SIZE)) {
                    context.ensureActive()
                    val computed = chunk.mapNotNull { file ->
                        context.ensureActive()
                        val saved = scores[file.path]
                        if (!force && saved?.input == FileScoreInput.from(file)) null
                        else scorer.score(file, clock())
                    }
                    if (computed.isNotEmpty()) {
                        val accepted = repository.saveFileScores(computed)
                        computed.forEach { score ->
                            if (score.input.path in accepted) scores[score.input.path] = score
                            else {
                                scores.remove(score.input.path)
                                invalidated++
                            }
                        }
                    }
                }
                val presentation = buildMap {
                    for (group in groups) {
                        context.ensureActive()
                        putAll(presentRetentionGroup(group, scores, scorer.identity, scorer.ambiguityMargin))
                    }
                }
                RetentionAssessmentResult(presentation, invalidated)
            }
        }
    }

    private companion object {
        const val SCORE_WRITE_BATCH_SIZE = 64
    }
}
