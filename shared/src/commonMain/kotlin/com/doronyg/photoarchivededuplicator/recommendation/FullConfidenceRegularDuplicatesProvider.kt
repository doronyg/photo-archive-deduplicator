package com.doronyg.photoarchivededuplicator.recommendation

import com.doronyg.photoarchivededuplicator.retention.RetentionScorer
import com.doronyg.photoarchivededuplicator.strings.AppStrings
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class FullConfidenceRegularDuplicatesProvider : DeletionRecommendationProvider {
    override val category = DeletionRecommendationCategory(
        "regular-duplicates-full-confidence",
        AppStrings.review.fullConfidenceRegularName,
        AppStrings.review.fullConfidenceRegularDescription,
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
        if (snapshot.hasProtectedMember()) return null
        val files = snapshot.files
        val proposal = snapshot.recommendation
        if (snapshot.source != source || files.size < 2 ||
            files.map { it.path }.distinct().size != files.size ||
            files.map { it.name }.distinct().size != 1 ||
            files.map { it.sizeBytes }.distinct().size != 1 ||
            proposal.duplicateConfidence.score != 100 || proposal.duplicateConfidence.hasContradictions) return null
        for (file in files) {
            val exif = currentRecommendationExif(file, snapshot.exif[file.path]) ?: return null
            if (currentRecommendationScore(file, exif, snapshot.retention[file.path], scorer) == null) return null
        }

        val safe = enforceRetentionPlanSafety(proposal, files,
            proposal.mustPreserve.map { it.directoryPath }.toSet())
        if (safe.action !in setOf(RecommendationAction.KEEP_ONE, RecommendationAction.KEEP_MULTIPLE) ||
            safe.redundantCandidates.isEmpty() ||
            safe.files.values.any { it.role == FileRecommendationRole.REVIEW }) return null

        fun present(advice: FileRecommendation) = DeletionReviewFile(advice,
            snapshot.exif[advice.file.path], snapshot.retention[advice.file.path],
            snapshot.directoryClassifications[advice.file.directoryPath])
        return DeletionRecommendationGroup(
            id = files.minOf { it.path.toString() },
            evidenceSummary = AppStrings.review.fullConfidenceRegularEvidence,
            survivors = safe.files.values.filter {
                it.role in setOf(FileRecommendationRole.PREFERRED, FileRecommendationRole.PRESERVE)
            }.map(::present),
            candidates = safe.files.values.filter { it.role == FileRecommendationRole.REDUNDANT_CANDIDATE }.map(::present),
            recommendation = safe,
        )
    }
}
