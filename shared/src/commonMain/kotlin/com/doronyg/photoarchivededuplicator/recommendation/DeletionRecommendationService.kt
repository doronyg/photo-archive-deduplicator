package com.doronyg.photoarchivededuplicator.recommendation

import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationContext
import com.doronyg.photoarchivededuplicator.retention.RetentionScoreService
import com.doronyg.photoarchivededuplicator.scan.DirectoryRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Stored evidence only. Final advice comes from the same engine used by duplicate browsing. */
internal class DeletionRecommendationService(
    private val repository: DirectoryRepository,
    private val retention: RetentionScoreService,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val classificationContext: DirectoryClassificationContext? = null,
    private val providers: List<DeletionRecommendationProvider> = listOf(SameDirectoryMetadataProvider()),
) {
    suspend fun load(): DeletionRecommendationReport = withContext(dispatcher) {
        val source = repository.suspectedDuplicates()
        val files = source.flatMap { it.files }
        val scores = retention.storedSuspected(source)
        val exif = repository.fileExif(files.map { it.path }, currentOnly = true).associateBy { it.input.path }
        val classifications = classificationContext?.let {
            repository.directoryClassifications(files.map { file -> file.directoryPath }.distinct(), it)
                .associateBy { record -> record.directoryPath }
        }.orEmpty()
        val recommendations = recommendDuplicateGroups(source.map { it.files }, exif, scores.files,
            dispatcher = dispatcher, directoryClassifications = classifications)
        val snapshots = source.map { group ->
            DeletionAnalysisSnapshot(group.files, recommendations.getValue(group.files.first().path), exif, scores.files, classifications)
        }
        buildDeletionRecommendationReport(snapshots, providers, dispatcher)
    }
}
