package com.doronyg.photoarchivededuplicator.recommendation

import com.doronyg.photoarchivededuplicator.classification.DirectoryClassification
import com.doronyg.photoarchivededuplicator.retention.RetentionScorer
import com.doronyg.photoarchivededuplicator.strings.AppStrings
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.Path

internal class ExifFolderDateMismatchProvider : DeletionRecommendationProvider {
    override val category = DeletionRecommendationCategory(
        "exif-folder-date-mismatch",
        AppStrings.review.folderMismatchName,
        AppStrings.review.folderMismatchDescription,
    )
    override val source = DeletionRecommendationSource.REGULAR
    private val scorer = RetentionScorer()

    override suspend fun recommendations(snapshots: List<DeletionAnalysisSnapshot>): List<DeletionRecommendationGroup> {
        val task = currentCoroutineContext()
        return snapshots.mapNotNull {
            task.ensureActive()
            recommend(it)
        }
    }

    fun recommend(snapshot: DeletionAnalysisSnapshot): DeletionRecommendationGroup? {
        val files = snapshot.files
        if (files.any {
            snapshot.directoryClassifications[it.directoryPath]?.classification == DirectoryClassification.PROTECTED
        }) return null
        if (snapshot.source != source || files.size < 2 ||
            files.map { it.path }.distinct().size != files.size ||
            snapshot.recommendation.action != RecommendationAction.KEEP_ONE) return null

        val captures = mutableMapOf<Path, String>()
        val folders = mutableMapOf<Path, String>()
        for (file in files) {
            val exif = currentRecommendationExif(file, snapshot.exif[file.path]) ?: return null
            captures[file.path] = scorer.usableExifDate(file, exif) ?: return null
            folders[file.path] = scorer.directoryEvidence(file).date ?: return null
            if (currentRecommendationScore(file, exif, snapshot.retention[file.path], scorer) == null) return null
        }
        val captureDate = captures.values.distinct().singleOrNull() ?: return null
        val proposal = snapshot.recommendation
        val safe = enforceRetentionPlanSafety(proposal, files, proposal.mustPreserve.map { it.directoryPath }.toSet())
        val survivor = safe.preferredFile ?: return null
        if (safe.action != RecommendationAction.KEEP_ONE || safe.duplicateConfidence.score < 75 ||
            safe.duplicateConfidence.hasContradictions || folders[survivor.path] != captureDate) return null
        val candidates = safe.files.values.filter {
            it.role == FileRecommendationRole.REDUNDANT_CANDIDATE && folders[it.file.path] != captureDate
        }
        if (candidates.isEmpty() || candidates.any { it.retainedReplacementPath != survivor.path }) return null

        fun present(advice: FileRecommendation): DeletionReviewFile {
            val folderDate = folders.getValue(advice.file.path)
            return DeletionReviewFile(advice, snapshot.exif[advice.file.path], snapshot.retention[advice.file.path],
                snapshot.directoryClassifications[advice.file.directoryPath], evidence = listOf(
                    AppStrings.review.directoryDate to folderDate,
                    AppStrings.review.captureDate to captureDate,
                    AppStrings.review.dateComparison to if (folderDate == captureDate) AppStrings.review.datesMatch else AppStrings.review.datesDiffer,
                ))
        }
        return DeletionRecommendationGroup(
            id = survivor.path.toString(),
            evidenceSummary = AppStrings.review.folderMismatchEvidence(captureDate),
            survivors = listOf(present(safe.files.getValue(survivor.path))),
            candidates = candidates.map(::present),
            recommendation = safe,
        )
    }
}
