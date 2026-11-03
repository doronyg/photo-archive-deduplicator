package com.doronyg.photoarchivededuplicator.exif

import com.doronyg.photoarchivededuplicator.retention.FileScoreInput
import com.doronyg.photoarchivededuplicator.retention.RetentionPresentation
import com.doronyg.photoarchivededuplicator.retention.RetentionRank
import com.doronyg.photoarchivededuplicator.retention.RetentionScorer
import com.doronyg.photoarchivededuplicator.retention.ScoreIdentity
import com.doronyg.photoarchivededuplicator.scan.DuplicateCandidateGroup
import okio.Path

internal const val EXIF_QUEUE_MAX_SCORE_GAP = 10.0
private val directoryDateScorer = RetentionScorer()

/** Presentations must come from the current initial assessment, not persisted UI snapshots. */
internal fun isExifQueueEligible(
    group: DuplicateCandidateGroup,
    presentations: Map<Path, RetentionPresentation>,
    maxScoreGap: Double = EXIF_QUEUE_MAX_SCORE_GAP,
    currentIdentity: ScoreIdentity = directoryDateScorer.identity,
): Boolean {
    require(maxScoreGap.isFinite() && maxScoreGap >= 0)
    if (group.files.size < 2) return false
    val scores = group.files.map { file ->
        val presentation = presentations[file.path] ?: return false
        if (presentation.rank in setOf(RetentionRank.MISSING, RetentionRank.STALE, RetentionRank.INCOMPLETE)) return false
        val score = presentation.score ?: return false
        if (score.identity != currentIdentity || score.input != FileScoreInput.from(file) || !score.value.isFinite()) return false
        score
    }
    val top = scores.map { it.value }.sortedDescending()
    return top[0] - top[1] <= maxScoreGap && group.files.any(directoryDateScorer::hasCompleteDirectoryDate)
}
