package com.doronyg.photoarchivededuplicator.ui

import com.doronyg.photoarchivededuplicator.operations.history.OperationHistorySnapshot
import com.doronyg.photoarchivededuplicator.recommendation.AiRecommendationStatistics
import com.doronyg.photoarchivededuplicator.recommendation.InventoryFileCounts
import com.doronyg.photoarchivededuplicator.recommendation.StatisticsFileType

internal data class CleanupRecordedMoves(
    val completedMoveEvents: Long,
    val filesInBin: Long,
    val logicalBytesInBin: Long,
    val partial: Boolean,
)

internal data class CleanupAiImpact(
    val changedGroups: Long,
    val changedFiles: Long,
    val baselineCandidates: Long,
    val currentCandidates: Long,
    val newCandidates: Long,
    val withdrawnCandidates: Long,
)

internal data class CleanupDuplicateType(
    val type: StatisticsFileType,
    val count: Long,
    /** Share of the deduplicated possible-duplicate union, not the entire inventory. */
    val fraction: Float,
)

internal data class CleanupResultsPresentation(
    val inventoryFiles: Long,
    val archiveBytes: Long,
    val possibleDuplicateFiles: Long,
    val possibleDuplicateFraction: Float,
    val duplicateTypes: List<CleanupDuplicateType>,
    val history: CleanupRecordedMoves?,
    val aiRegular: CleanupAiImpact?,
    val aiSuspected: CleanupAiImpact?,
)

/** History must belong to the active Bin; the caller owns matching it and its availability. */
internal fun presentCleanupResults(
    snapshot: CleanupResultsSnapshot,
    history: OperationHistorySnapshot?,
): CleanupResultsPresentation {
    val report = snapshot.analysis
    val duplicates = report.inventory.all.possibleDuplicateFiles
    require(snapshot.inventory.totalBytes >= 0) { "Archive size must be nonnegative." }
    val recorded = history?.takeIf { it.readable && it.identity != null }?.let {
        CleanupRecordedMoves(
            completedMoveEvents = it.statistics.completedMoves,
            filesInBin = it.statistics.filesInBin,
            logicalBytesInBin = it.statistics.logicalBytesInBin,
            partial = !it.complete || it.problems.isNotEmpty(),
        )
    }
    return CleanupResultsPresentation(
        inventoryFiles = snapshot.inventory.fileCount,
        archiveBytes = snapshot.inventory.totalBytes,
        possibleDuplicateFiles = duplicates,
        possibleDuplicateFraction = cleanupFraction(duplicates, snapshot.inventory.fileCount),
        duplicateTypes = StatisticsFileType.entries.map { type ->
            val count = report.inventory.byFileType.getValue(type).possibleDuplicateFiles
            CleanupDuplicateType(type, count, cleanupFraction(count, duplicates))
        },
        history = recorded,
        aiRegular = report.regular.ai?.takeIf { report.classificationContext != null }?.cleanupImpact(),
        aiSuspected = report.suspected.ai?.takeIf { report.classificationContext != null }?.cleanupImpact(),
    )
}

private fun cleanupFraction(count: Long, total: Long): Float {
    val counts = InventoryFileCounts(total, count)
    return if (counts.totalFiles == 0L) 0f
        else (counts.possibleDuplicateFiles.toDouble() / counts.totalFiles.toDouble()).toFloat()
}

private fun AiRecommendationStatistics.cleanupImpact() = CleanupAiImpact(
    changedGroups, changedFiles.fileCount, baselineRedundantCandidates.fileCount,
    currentRedundantCandidates.fileCount, addedRedundantCandidates.fileCount,
    withdrawnRedundantCandidates.fileCount,
)

internal fun formatCleanupCount(count: Long): String {
    val text = count.toString()
    val sign = if (text.startsWith('-')) "-" else ""
    return sign + text.removePrefix("-").reversed().chunked(3).joinToString(",").reversed()
}

/** Decimal SI units, rounded to one decimal with trailing zero omitted. */
internal fun formatCleanupBytes(bytes: Long): String {
    require(bytes >= 0)
    if (bytes < 1000L) return "$bytes B"
    val units = listOf("B", "kB", "MB", "GB", "TB", "PB", "EB")
    var unit = 0
    var divisor = 1L
    while (unit < units.lastIndex && bytes / divisor >= 1000L) {
        divisor *= 1000L
        unit++
    }
    // Divide before rounding so even Long.MAX_VALUE never needs a multiplication.
    var tenths = (bytes / divisor) * 10L +
        ((bytes % divisor) + divisor / 20L) / (divisor / 10L)
    if (tenths >= 10_000L && unit < units.lastIndex) {
        unit++
        tenths = 10L
    }
    val number = if (tenths % 10L == 0L) "${tenths / 10L}" else "${tenths / 10L}.${tenths % 10L}"
    return "$number ${units[unit]}"
}
