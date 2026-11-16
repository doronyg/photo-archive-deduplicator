package com.doronyg.photoarchivededuplicator.recommendation

import com.doronyg.photoarchivededuplicator.strings.AppStrings
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class DirectorySubdividedMetadataProvider : DeletionRecommendationProvider {
    override val category = DeletionRecommendationCategory(
        "same-directory-metadata-by-directory",
        AppStrings.review.directorySubdividedName,
        AppStrings.review.directorySubdividedDescription,
    )
    private val sameDirectory = SameDirectoryMetadataProvider()
    private val engine = DuplicateRecommendationEngine()

    override suspend fun recommendations(snapshots: List<DeletionAnalysisSnapshot>): List<DeletionRecommendationGroup> {
        val task = currentCoroutineContext()
        return snapshots.mapNotNull {
            task.ensureActive()
            recommend(it)
        }.distinctBy { it.recommendation.files.keys }
    }

    fun recommend(snapshot: DeletionAnalysisSnapshot): DeletionRecommendationGroup? {
        val files = snapshot.files.sortedBy { it.path.toString() }
        val expected = files.associateBy { it.path }
        val directories = files.groupBy { it.directoryPath }
        if (directories.size < 2 || expected.size != files.size ||
            snapshot.hasProtectedMember() || snapshot.recommendation.action == RecommendationAction.PROTECTED ||
            RecommendationReason.EXPLICIT_PROTECTION in snapshot.recommendation.reasonCodes ||
            snapshot.recommendation.files.keys != expected.keys ||
            snapshot.recommendation.files.any { (path, advice) ->
                advice.file != expected[path] || RecommendationReason.EXPLICIT_PROTECTION in advice.reasonCodes
            }) return null

        val localGroups = directories.values.mapNotNull { members ->
            if (members.size < 2) return@mapNotNull null
            val recommendation = engine.recommend(members, snapshot.exif, snapshot.retention,
                directoryClassifications = snapshot.directoryClassifications)
            sameDirectory.recommend(snapshot.copy(files = members, recommendation = recommendation))
        }
        if (localGroups.isEmpty()) return null
        val localAdvice = localGroups.flatMap { it.recommendation.files.values }.associateBy { it.file.path }
        val combinedAdvice = files.associate { file ->
            val advice = localAdvice[file.path]
            file.path to when {
                advice == null -> FileRecommendation(
                    file, FileRecommendationRole.PRESERVE, normalizedRecommendationFilename(file.name),
                    setOf(RecommendationReason.METADATA_ONLY, RecommendationReason.MULTIPLE_RETAINED_LOCATIONS),
                    AppStrings.review.directorySubdividedPreserved,
                    ConfidenceAssessment(0, setOf(ConfidenceReason.COLLECTION_REPRESENTATIVE,
                        ConfidenceReason.EVIDENCE_UNAVAILABLE), AppStrings.review.directorySubdividedPreserved),
                )
                advice.role == FileRecommendationRole.PREFERRED -> advice.copy(
                    role = FileRecommendationRole.PRESERVE,
                    reasonCodes = advice.reasonCodes + RecommendationReason.MULTIPLE_RETAINED_LOCATIONS,
                    retentionDecisionConfidence = advice.retentionDecisionConfidence.copy(
                        reasonCodes = advice.retentionDecisionConfidence.reasonCodes -
                            ConfidenceReason.DESIGNATED_SURVIVOR + ConfidenceReason.COLLECTION_REPRESENTATIVE),
                )
                else -> advice
            }
        }
        val localConfidence = localGroups.map { it.recommendation.duplicateConfidence }
        val weakest = localConfidence.minBy { it.score }
        // Confidence describes local comparisons only, never identity between different folders.
        val confidence = DuplicateConfidenceAssessment(
            referenceFile = null,
            assessment = weakest.assessment.copy(
                reasonCodes = localConfidence.flatMap { it.assessment.reasonCodes }.toSet(),
                explanation = AppStrings.review.directorySubdividedConfidence(weakest.score),
            ),
            comparisons = localConfidence.flatMap { it.comparisons.entries }.associate { it.toPair() },
        )
        val proposal = DuplicateGroupRecommendation(
            action = RecommendationAction.KEEP_MULTIPLE,
            evidenceStrength = if (localGroups.all { it.recommendation.evidenceStrength == EvidenceStrength.STRONG })
                EvidenceStrength.STRONG else EvidenceStrength.SUPPORTED,
            reasonCodes = localGroups.flatMap { it.recommendation.reasonCodes }.toSet() +
                RecommendationReason.MULTIPLE_RETAINED_LOCATIONS,
            explanation = AppStrings.review.directorySubdividedEvidence,
            files = combinedAdvice,
            duplicateConfidence = confidence,
        )
        val safe = enforceRetentionPlanSafety(proposal, files, directories.keys)
        if (safe.action != RecommendationAction.KEEP_MULTIPLE || safe.redundantCandidates.isEmpty()) return null
        fun present(advice: FileRecommendation) = DeletionReviewFile(advice,
            snapshot.exif[advice.file.path], snapshot.retention[advice.file.path],
            snapshot.directoryClassifications[advice.file.directoryPath])
        return DeletionRecommendationGroup(
            id = files.first().path.toString(),
            evidenceSummary = AppStrings.review.directorySubdividedEvidence,
            survivors = safe.files.values.filter { it.role == FileRecommendationRole.PRESERVE }.map(::present),
            candidates = safe.files.values.filter { it.role == FileRecommendationRole.REDUNDANT_CANDIDATE }.map(::present),
            recommendation = safe,
        )
    }
}
