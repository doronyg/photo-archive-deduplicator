package com.doronyg.photoarchivededuplicator.recommendation

import com.doronyg.photoarchivededuplicator.classification.DirectoryClassification
import com.doronyg.photoarchivededuplicator.retention.RetentionScorer
import com.doronyg.photoarchivededuplicator.strings.AppStrings
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class SingleProtectedDirectoryProvider : DeletionRecommendationProvider {
    override val category = DeletionRecommendationCategory(
        "single-protected-directory", AppStrings.review.singleProtectedName, AppStrings.review.singleProtectedDescription)
    override val sources = DeletionRecommendationSource.entries.toSet()
    override val allowsProtectedGroups = true
    private val scorer = RetentionScorer()
    private val engine = DuplicateRecommendationEngine(scorer)

    override suspend fun recommendations(snapshots: List<DeletionAnalysisSnapshot>): List<DeletionRecommendationGroup> {
        val task = currentCoroutineContext()
        return snapshots.mapNotNull {
            task.ensureActive()
            recommend(it)
        }.distinctBy { it.recommendation.files.keys }
    }

    fun recommend(snapshot: DeletionAnalysisSnapshot): DeletionRecommendationGroup? {
        val files = snapshot.files
        val directory = files.map { it.directoryPath }.distinct().singleOrNull() ?: return null
        val classification = snapshot.directoryClassifications[directory] ?: return null
        if (classification.directoryPath != directory || classification.classification != DirectoryClassification.PROTECTED ||
            files.size < 2 || files.map { it.path }.distinct().size != files.size ||
            files.any { it.path.parent != directory } ||
            snapshot.recommendation.files.keys != files.map { it.path }.toSet() ||
            snapshot.recommendation.files.values.map { it.file }.toSet() != files.toSet() ||
            snapshot.recommendation.action == RecommendationAction.PROTECTED ||
            snapshot.recommendation.reasonCodes.contains(RecommendationReason.EXPLICIT_PROTECTION) ||
            snapshot.recommendation.files.values.any { RecommendationReason.EXPLICIT_PROTECTION in it.reasonCodes } ||
            snapshot.recommendation.duplicateConfidence.score < 75 ||
            snapshot.recommendation.duplicateConfidence.hasContradictions) return null
        for (file in files) {
            val exif = currentRecommendationExif(file, snapshot.exif[file.path]) ?: return null
            if (currentRecommendationScore(file, exif, snapshot.retention[file.path], scorer) == null) return null
        }
        // Only the decision loses this classification; saved review/operation evidence keeps it.
        val plan = engine.recommend(files, snapshot.exif, snapshot.retention,
            directoryClassifications = snapshot.directoryClassifications - directory)
        val survivor = plan.preferredFile ?: return null
        if (plan.action != RecommendationAction.KEEP_ONE || plan.mustPreserve.size != 1 ||
            plan.redundantCandidates.size != files.size - 1 ||
            plan.duplicateConfidence.score < 75 || plan.duplicateConfidence.hasContradictions ||
            plan.files.values.any { it.retentionDecisionConfidence.score < 75 } ||
            plan.files.values.any { it.role == FileRecommendationRole.REDUNDANT_CANDIDATE &&
                it.retainedReplacementPath != survivor.path }) return null
        fun present(advice: FileRecommendation) = DeletionReviewFile(advice, snapshot.exif[advice.file.path],
            snapshot.retention[advice.file.path], classification)
        return DeletionRecommendationGroup(
            id = "${snapshot.source.name}:${files.minOf { it.path.toString() }}",
            evidenceSummary = AppStrings.review.singleProtectedEvidence,
            survivors = listOf(present(plan.files.getValue(survivor.path))),
            candidates = plan.files.values.filter { it.role == FileRecommendationRole.REDUNDANT_CANDIDATE }.map(::present),
            recommendation = plan,
            policy = DeletionRecommendationPolicy.SINGLE_PROTECTED_DIRECTORY,
        )
    }
}
