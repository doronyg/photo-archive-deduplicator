package com.doronyg.photoarchivededuplicator.retention

import com.doronyg.photoarchivededuplicator.scan.DuplicateCandidateGroup
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import com.doronyg.photoarchivededuplicator.scan.SuspectedDuplicateGroup
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.Path

private data class RetentionFileOrder(val file: FileRecord, val value: Double?, val path: String)

/** Sorts members only; unavailable/outdated scores follow even negative current scores. */
internal suspend fun sortDuplicateFilesByRetention(
    groups: List<DuplicateCandidateGroup>,
    retention: Map<Path, RetentionPresentation>,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): List<DuplicateCandidateGroup> = withContext(dispatcher) {
    val context = currentCoroutineContext()
    groups.map { group ->
        context.ensureActive()
        group.copy(files = sortedRetentionFiles(group.files, retention))
    }
}

internal suspend fun sortSuspectedFilesByRetention(
    groups: List<SuspectedDuplicateGroup>,
    retention: Map<Path, RetentionPresentation>,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): List<SuspectedDuplicateGroup> = withContext(dispatcher) {
    val context = currentCoroutineContext()
    groups.map { group ->
        context.ensureActive()
        group.copy(files = sortedRetentionFiles(group.files, retention))
    }
}

private suspend fun sortedRetentionFiles(
    files: List<FileRecord>,
    retention: Map<Path, RetentionPresentation>,
): List<FileRecord> {
    val context = currentCoroutineContext()
    val members = files.map { file ->
        context.ensureActive()
        val presentation = retention[file.path]
        val score = presentation?.score?.takeIf {
            presentation.rank != RetentionRank.MISSING && presentation.rank != RetentionRank.STALE &&
                it.input == FileScoreInput.from(file)
        }
        RetentionFileOrder(file, score?.value, file.path.toString())
    }
    return members.sortedWith { a, b ->
        context.ensureActive()
        val comparison = when {
            a.value == null && b.value == null -> 0
            a.value == null -> 1
            b.value == null -> -1
            else -> b.value.compareTo(a.value)
        }
        if (comparison != 0) comparison else a.path.compareTo(b.path)
    }.map {
        context.ensureActive()
        it.file
    }
}
