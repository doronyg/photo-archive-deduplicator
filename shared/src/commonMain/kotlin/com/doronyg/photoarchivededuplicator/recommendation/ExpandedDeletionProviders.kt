package com.doronyg.photoarchivededuplicator.recommendation

import com.doronyg.photoarchivededuplicator.classification.DirectoryClassification
import com.doronyg.photoarchivededuplicator.retention.RetentionScorer
import com.doronyg.photoarchivededuplicator.strings.AppStrings
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.Path

internal class FolderPreservingDuplicatesProvider : DeletionRecommendationProvider {
    override val category = DeletionRecommendationCategory(
        "folder-preserving-duplicates",
        AppStrings.review.folderPreservingName,
        AppStrings.review.folderPreservingDescription,
    )
    override val sources = DeletionRecommendationSource.entries.toSet()
    private val scorer = RetentionScorer()

    override suspend fun recommendations(snapshots: List<DeletionAnalysisSnapshot>): List<DeletionRecommendationGroup> {
        val task = currentCoroutineContext()
        return snapshots.mapNotNull {
            task.ensureActive()
            recommend(it)
        }.distinctBy { it.recommendation.files.keys }
    }

    fun recommend(snapshot: DeletionAnalysisSnapshot): DeletionRecommendationGroup? {
        val directories = snapshot.files.map { it.directoryPath }.toSet()
        if (directories.size < 2 || snapshot.recommendation.action != RecommendationAction.KEEP_MULTIPLE) return null
        val plan = currentSafeDeletionPlan(snapshot, scorer, directories) ?: return null
        return presentDeletionPlan(snapshot, plan, AppStrings.review.folderPreservingEvidence)
    }
}

internal class PreferredBackupPerfectScoreProvider : DeletionRecommendationProvider {
    override val category = DeletionRecommendationCategory(
        "preferred-backup-perfect-score",
        AppStrings.review.preferredBackupPerfectName,
        AppStrings.review.preferredBackupPerfectDescription,
    )
    override val sources = DeletionRecommendationSource.entries.toSet()
    private val scorer = RetentionScorer()

    override suspend fun recommendations(snapshots: List<DeletionAnalysisSnapshot>): List<DeletionRecommendationGroup> {
        val task = currentCoroutineContext()
        return snapshots.mapNotNull {
            task.ensureActive()
            recommend(it)
        }.distinctBy { it.recommendation.files.keys }
    }

    fun recommend(snapshot: DeletionAnalysisSnapshot): DeletionRecommendationGroup? {
        if (snapshot.files.any { file ->
            val record = snapshot.directoryClassifications[file.directoryPath]
            record == null || record.directoryPath != file.directoryPath ||
                record.classification !in setOf(DirectoryClassification.PREFERRED, DirectoryClassification.BACKUP)
        }) return null
        val plan = currentSafeDeletionPlan(snapshot, scorer,
            snapshot.recommendation.mustPreserve.map { it.directoryPath }.toSet()) ?: return null
        if (plan.mustPreserve.none {
            val score = snapshot.retention[it.path]?.score
            score != null && score.value >= 100.0
        }) return null
        return presentDeletionPlan(snapshot, plan, AppStrings.review.preferredBackupPerfectEvidence)
    }
}

private fun currentSafeDeletionPlan(
    snapshot: DeletionAnalysisSnapshot,
    scorer: RetentionScorer,
    requiredDirectories: Set<Path>,
): DuplicateGroupRecommendation? {
    val files = snapshot.files
    val proposal = snapshot.recommendation
    if (snapshot.hasProtectedMember() || files.size < 2 ||
        files.map { it.path }.distinct().size != files.size ||
        files.map { it.sizeBytes }.distinct().size != 1 ||
        proposal.action !in setOf(RecommendationAction.KEEP_ONE, RecommendationAction.KEEP_MULTIPLE) ||
        proposal.duplicateConfidence.score < 75 || proposal.duplicateConfidence.hasContradictions) return null
    for (file in files) {
        val exif = currentRecommendationExif(file, snapshot.exif[file.path]) ?: return null
        if (currentRecommendationScore(file, exif, snapshot.retention[file.path], scorer) == null) return null
    }
    val plan = enforceRetentionPlanSafety(proposal, files, requiredDirectories)
    return plan.takeIf {
        it.action in setOf(RecommendationAction.KEEP_ONE, RecommendationAction.KEEP_MULTIPLE) &&
            it.redundantCandidates.isNotEmpty() &&
            it.files.values.none { advice -> advice.role == FileRecommendationRole.REVIEW }
    }
}

private fun presentDeletionPlan(
    snapshot: DeletionAnalysisSnapshot,
    plan: DuplicateGroupRecommendation,
    evidence: String,
): DeletionRecommendationGroup {
    fun present(advice: FileRecommendation) = DeletionReviewFile(advice,
        snapshot.exif[advice.file.path], snapshot.retention[advice.file.path],
        snapshot.directoryClassifications[advice.file.directoryPath])
    return DeletionRecommendationGroup(
        id = "${snapshot.source.name}:${snapshot.files.minOf { it.path.toString() }}",
        evidenceSummary = evidence,
        survivors = plan.files.values.filter {
            it.role in setOf(FileRecommendationRole.PREFERRED, FileRecommendationRole.PRESERVE)
        }.map(::present),
        candidates = plan.files.values.filter { it.role == FileRecommendationRole.REDUNDANT_CANDIDATE }.map(::present),
        recommendation = plan,
    )
}
