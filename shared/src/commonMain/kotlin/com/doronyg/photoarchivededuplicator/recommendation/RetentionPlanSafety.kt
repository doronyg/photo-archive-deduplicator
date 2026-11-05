package com.doronyg.photoarchivededuplicator.recommendation

import com.doronyg.photoarchivededuplicator.scan.FileRecord
import com.doronyg.photoarchivededuplicator.strings.AppStrings
import okio.Path

/** Validate the complete plan, not independent candidate flags that could discard every copy. */
internal fun enforceRetentionPlanSafety(
    proposal: DuplicateGroupRecommendation,
    expectedFiles: List<FileRecord>,
    requiredDirectories: Set<Path>,
    protectedPaths: Set<Path> = emptySet(),
): DuplicateGroupRecommendation {
    val expected = expectedFiles.associateBy { it.path }
    val retained = proposal.files.values.filter { it.role != FileRecommendationRole.REDUNDANT_CANDIDATE }
    val survivingDirectories = retained.map { it.file.directoryPath }.toSet()
    val candidates = proposal.files.values.filter { it.role == FileRecommendationRole.REDUNDANT_CANDIDATE }
    val changedInputs = proposal.files.keys != expected.keys || proposal.files.any { (path, advice) ->
        advice.file != expected[path] || advice.file.path != path
    }
    val problems = buildList {
        if (changedInputs) add(AppStrings.recommendations.planChangedInputs)
        if (expected.isNotEmpty() && retained.isEmpty()) add(AppStrings.recommendations.planNoCopy)
        val missingDirectories = requiredDirectories - survivingDirectories
        if (missingDirectories.isNotEmpty()) add(AppStrings.recommendations.missingCollectionRepresentatives(missingDirectories.sortedBy { it.toString() }))
        for (advice in proposal.files.values) {
            val replacement = advice.retainedReplacementPath
            if (advice.role != FileRecommendationRole.REDUNDANT_CANDIDATE) {
                if (replacement != null) add(AppStrings.recommendations.preservedFileDependency(advice.file.path))
                continue
            }
            if (advice.file.path in protectedPaths) add(AppStrings.recommendations.protectedFileRedundant(advice.file.path))
            val keeper = replacement?.let { proposal.files[it] }
            if (replacement == advice.file.path || keeper == null ||
                keeper.role !in setOf(FileRecommendationRole.PREFERRED, FileRecommendationRole.PRESERVE)) {
                add(AppStrings.recommendations.missingReplacement(advice.file.path))
            }
            if (advice.retentionDecisionConfidence.score < 75) {
                add(AppStrings.recommendations.lowDecisionConfidence(advice.file.path))
            }
        }
        when (proposal.action) {
            RecommendationAction.KEEP_ONE ->
                if (retained.size != 1 || retained.singleOrNull()?.role != FileRecommendationRole.PREFERRED)
                    add(AppStrings.recommendations.invalidKeepOne)
            RecommendationAction.KEEP_MULTIPLE ->
                if (retained.size < 2 || retained.any { it.role == FileRecommendationRole.PREFERRED })
                    add(AppStrings.recommendations.invalidKeepMultiple)
            RecommendationAction.REVIEW, RecommendationAction.PROTECTED ->
                if (candidates.isNotEmpty()) add(AppStrings.recommendations.invalidReviewCandidates)
        }
    }
    if (problems.isEmpty()) return proposal

    val explanation = AppStrings.recommendations.rejectedPlan(problems)
    val confidence = ConfidenceAssessment(0, setOf(ConfidenceReason.RETENTION_PLAN_INVALID), explanation)
    return proposal.copy(
        action = RecommendationAction.REVIEW,
        evidenceStrength = EvidenceStrength.INSUFFICIENT,
        reasonCodes = proposal.reasonCodes + RecommendationReason.RETENTION_PLAN_INVALID,
        explanation = explanation,
        files = expected.mapValues { (_, file) ->
            FileRecommendation(file, FileRecommendationRole.REVIEW, normalizedRecommendationFilename(file.name),
                setOf(RecommendationReason.RETENTION_PLAN_INVALID), explanation, confidence)
        },
        duplicateConfidence = if (changedInputs) DuplicateConfidenceAssessment(null, ConfidenceAssessment(0,
            setOf(ConfidenceReason.EVIDENCE_UPDATING),
            AppStrings.recommendations.reassessChangedGroup), emptyMap())
        else proposal.duplicateConfidence,
    )
}
