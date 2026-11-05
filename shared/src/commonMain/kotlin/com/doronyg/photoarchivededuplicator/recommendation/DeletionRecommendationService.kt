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
    private val providers: List<DeletionRecommendationProvider> =
        listOf(SameDirectoryMetadataProvider(), ExifFolderDateMismatchProvider(), FullConfidenceRegularDuplicatesProvider()),
) {
    suspend fun load(): DeletionRecommendationReport = withContext(dispatcher) {
        val snapshots = providers.map { it.source }.distinct().flatMap { snapshots(it) }
        buildDeletionRecommendationReport(snapshots, providers, dispatcher)
    }

    private suspend fun snapshots(source: DeletionRecommendationSource): List<DeletionAnalysisSnapshot> {
        val (groups, scores) = when (source) {
            DeletionRecommendationSource.REGULAR -> {
                val records = repository.duplicateCandidates()
                records.map { it.files } to retention.stored(records).files
            }
            DeletionRecommendationSource.SUSPECTED -> {
                val records = repository.suspectedDuplicates()
                records.map { it.files } to retention.storedSuspected(records).files
            }
        }
        val files = groups.flatten()
        val exif = repository.fileExif(files.map { it.path }, currentOnly = true).associateBy { it.input.path }
        val classifications = classificationContext?.let {
            repository.directoryClassifications(files.map { file -> file.directoryPath }.distinct(), it)
                .associateBy { record -> record.directoryPath }
        }.orEmpty()
        val recommendations = recommendDuplicateGroups(groups, exif, scores,
            dispatcher = dispatcher, directoryClassifications = classifications)
        return groups.map { group ->
            DeletionAnalysisSnapshot(group, recommendations.getValue(group.first().path), exif, scores, classifications, source)
        }
    }
}
