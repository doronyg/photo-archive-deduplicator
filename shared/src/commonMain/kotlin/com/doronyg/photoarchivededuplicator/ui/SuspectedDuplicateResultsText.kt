package com.doronyg.photoarchivededuplicator.ui

import com.doronyg.photoarchivededuplicator.exif.ExifRecord
import com.doronyg.photoarchivededuplicator.recommendation.DuplicateGroupRecommendation
import com.doronyg.photoarchivededuplicator.retention.RetentionPresentation
import com.doronyg.photoarchivededuplicator.scan.DuplicateSortRule
import com.doronyg.photoarchivededuplicator.scan.SUSPECTED_IMAGE_MIN_SIZE_BYTES
import com.doronyg.photoarchivededuplicator.scan.SuspectedDuplicateFilter
import com.doronyg.photoarchivededuplicator.scan.SuspectedDuplicateGroup
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.Path

/** Copies exactly the displayed whole-group snapshot, including off-screen members. */
internal suspend fun formatSuspectedDuplicateResults(
    filter: SuspectedDuplicateFilter,
    rules: List<DuplicateSortRule>,
    groups: List<SuspectedDuplicateGroup>,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    retention: Map<Path, RetentionPresentation> = emptyMap(),
    exif: Map<Path, ExifRecord> = emptyMap(),
    recommendations: Map<Path, DuplicateGroupRecommendation> = emptyMap(),
): String = withContext(dispatcher) {
    val context = currentCoroutineContext()
    buildString {
        appendLine("Suspected duplicates - not content-verified")
        appendLine("Images larger than $SUSPECTED_IMAGE_MIN_SIZE_BYTES bytes, matched by exact byte size and current stored EXIF capture datetime to the second.")
        appendLine("Original offsets must match, with unknown timezones grouped separately. Groups contain different case-sensitive filenames.")
        appendLine("Stored inventory snapshot, excluding excluded folders. No files have been changed.")
        appendLine("Retention scores are advisory, not probabilities or deletion instructions. Contents have not been compared.")
        appendLine("Folder filter: ${filter.root?.toString()?.let(::quoteResultText) ?: "all stored roots"}")
        appendLine("Wildcard (case-insensitive): ${quoteResultText(filter.filenamePattern.trim().ifEmpty { "*" })}")
        appendLine("Recommendation filter: ${filter.recommendation?.name ?: "ALL"}")
        appendLine("Whole groups are retained; copies may be outside the folder filter or have other filenames.")
        appendDuplicateSortContext(rules)
        appendLine("Candidate groups: ${groups.size}")
        groups.forEachIndexed { index, group ->
            context.ensureActive()
            appendLine()
            appendLine("Group ${index + 1}: Captured ${quoteResultText(group.capturedAt)}")
            appendLine("Original offset: ${group.offsetOriginal?.let(::quoteResultText) ?: "unknown (timezone unknown)"}")
            appendLine("Size per file: ${group.sizeBytes} bytes (${formatBytes(group.sizeBytes)})")
            appendLine("Copies: ${group.files.size}")
            val recommendation = group.files.firstOrNull()?.let { recommendations[it.path] }
            appendGroupRecommendation(recommendation)
            for (file in group.files) {
                appendDuplicateFileResult(file, retention[file.path], exif[file.path], recommendation?.files?.get(file.path))
            }
        }
    }
}
