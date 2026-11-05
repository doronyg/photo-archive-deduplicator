package com.doronyg.photoarchivededuplicator.retention

import com.doronyg.photoarchivededuplicator.scan.DuplicateCandidateGroup
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import com.doronyg.photoarchivededuplicator.strings.AppStrings
import okio.Path
import kotlin.math.abs

enum class RetentionRank { HIGHEST, TIED, CLOSE, LOWER, MISSING, STALE, INCOMPLETE }

data class RetentionPresentation(val score: FileScore?, val rank: RetentionRank) {
    val label: String get() = when (rank) {
        RetentionRank.HIGHEST -> AppStrings.recommendations.highestRetentionScore
        RetentionRank.TIED -> AppStrings.recommendations.tiedRetentionScores
        RetentionRank.CLOSE -> AppStrings.recommendations.closeRetentionScores
        RetentionRank.LOWER -> AppStrings.recommendations.lowerRetentionScore
        RetentionRank.MISSING -> AppStrings.recommendations.notScored
        RetentionRank.STALE -> AppStrings.recommendations.outdatedScore
        RetentionRank.INCOMPLETE -> AppStrings.recommendations.incompleteRetentionGroup
    }

    val scoreText: String get() = score?.value?.let(::formatScoreValue) ?: AppStrings.recommendations.missingScore
    val mainReason: String get() = score?.factors?.filter { it.contribution != 0.0 }
        ?.sortedByDescending { abs(it.contribution) }?.take(2)?.joinToString("; ") { it.explanation }
        ?.takeIf { it.isNotBlank() } ?: AppStrings.recommendations.noRetentionEvidence
}

internal fun formatScoreValue(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

internal fun presentRetentionGroup(
    group: DuplicateCandidateGroup,
    scores: Map<Path, FileScore>,
    identity: ScoreIdentity,
    ambiguityMargin: Double,
): Map<Path, RetentionPresentation> = presentRetentionFiles(group.files, scores, identity, ambiguityMargin)

internal fun presentRetentionFiles(
    files: List<FileRecord>,
    scores: Map<Path, FileScore>,
    identity: ScoreIdentity,
    ambiguityMargin: Double,
): Map<Path, RetentionPresentation> {
    require(ambiguityMargin.isFinite() && ambiguityMargin >= 0) { "Invalid ambiguity margin" }
    val current = files.associate { file ->
        file.path to scores[file.path]?.takeIf { it.identity == identity && it.input == FileScoreInput.from(file) }
    }
    if (current.values.any { it == null }) {
        return files.associate { file ->
            val saved = scores[file.path]
            val rank = when {
                current[file.path] != null -> RetentionRank.INCOMPLETE
                saved != null -> RetentionRank.STALE
                else -> RetentionRank.MISSING
            }
            file.path to RetentionPresentation(saved, rank)
        }
    }
    val values = current.values.filterNotNull().map { it.value }.sortedDescending()
    val highest = values.firstOrNull() ?: return emptyMap()
    val tied = values.count { it == highest } > 1
    val closeLeader = !tied && values.size > 1 && highest - values[1] <= ambiguityMargin
    return current.mapValues { (_, score) ->
        val rank = when {
            score == null -> error("Complete group must have a score for every file")
            score.value == highest && tied -> RetentionRank.TIED
            score.value == highest && closeLeader -> RetentionRank.CLOSE
            score.value == highest -> RetentionRank.HIGHEST
            highest - score.value <= ambiguityMargin -> RetentionRank.CLOSE
            else -> RetentionRank.LOWER
        }
        RetentionPresentation(score, rank)
    }
}
