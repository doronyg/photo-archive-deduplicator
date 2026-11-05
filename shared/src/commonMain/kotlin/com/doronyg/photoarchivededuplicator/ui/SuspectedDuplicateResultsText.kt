package com.doronyg.photoarchivededuplicator.ui

import com.doronyg.photoarchivededuplicator.exif.ExifRecord
import com.doronyg.photoarchivededuplicator.recommendation.DuplicateGroupRecommendation
import com.doronyg.photoarchivededuplicator.retention.RetentionPresentation
import com.doronyg.photoarchivededuplicator.scan.DuplicateSortRule
import com.doronyg.photoarchivededuplicator.scan.SUSPECTED_IMAGE_MIN_SIZE_BYTES
import com.doronyg.photoarchivededuplicator.scan.SuspectedDuplicateFilter
import com.doronyg.photoarchivededuplicator.scan.SuspectedDuplicateGroup
import com.doronyg.photoarchivededuplicator.strings.AppStrings
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
        appendLine(AppStrings.duplicates.suspectedTitle)
        appendLine(AppStrings.duplicates.exportSuspectedScope(SUSPECTED_IMAGE_MIN_SIZE_BYTES))
        appendLine(AppStrings.duplicates.exportOffsets)
        appendLine(AppStrings.duplicates.exportFolder(filter.root?.toString()?.let(::quoteResultText) ?: AppStrings.duplicates.allRoots))
        appendLine(AppStrings.duplicates.exportWildcard(quoteResultText(filter.filenamePattern.trim().ifEmpty { "*" })))
        appendLine(AppStrings.duplicates.exportRecommendationFilter(filter.recommendation?.name ?: AppStrings.duplicates.all))
        appendLine(AppStrings.duplicates.exportWholeGroups)
        appendDuplicateSortContext(rules)
        appendLine(AppStrings.duplicates.exportGroupCount(groups.size))
        groups.forEachIndexed { index, group ->
            context.ensureActive()
            appendLine()
            appendLine(AppStrings.duplicates.exportCapturedGroup(index + 1, quoteResultText(group.capturedAt)))
            appendLine(AppStrings.duplicates.exportOffset(group.offsetOriginal?.let(::quoteResultText) ?: AppStrings.duplicates.timezoneUnknown))
            appendLine(AppStrings.duplicates.exportSize(group.sizeBytes, formatBytes(group.sizeBytes)))
            appendLine(AppStrings.duplicates.exportCopies(group.files.size))
            val recommendation = group.files.firstOrNull()?.let { recommendations[it.path] }
            appendGroupRecommendation(recommendation)
            for (file in group.files) {
                appendDuplicateFileResult(file, retention[file.path], exif[file.path], recommendation?.files?.get(file.path))
            }
        }
    }
}
