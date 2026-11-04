package com.doronyg.photoarchivededuplicator.recommendation

import com.doronyg.photoarchivededuplicator.exif.ExifRecord
import com.doronyg.photoarchivededuplicator.retention.RetentionPresentation
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.Path

/** Index each group result by its members so UI lookups do not depend on display ordering. */
internal suspend fun recommendDuplicateGroups(
    groups: List<List<FileRecord>>,
    exif: Map<Path, ExifRecord>,
    retention: Map<Path, RetentionPresentation>,
    evidenceReady: Boolean = true,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    protectedPaths: Set<Path> = emptySet(),
): Map<Path, DuplicateGroupRecommendation> = withContext(dispatcher) {
    val context = currentCoroutineContext()
    val engine = DuplicateRecommendationEngine()
    buildMap {
        for (files in groups) {
            context.ensureActive()
            val recommendation = engine.recommend(files, exif, retention, evidenceReady, protectedPaths)
            for (file in files) {
                context.ensureActive()
                put(file.path, recommendation)
            }
        }
    }
}
