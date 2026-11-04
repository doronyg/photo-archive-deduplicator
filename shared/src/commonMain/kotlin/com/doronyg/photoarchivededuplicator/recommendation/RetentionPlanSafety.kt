package com.doronyg.photoarchivededuplicator.recommendation

import com.doronyg.photoarchivededuplicator.scan.FileRecord
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
        if (changedInputs) add("The plan does not account for every original member with its current metadata.")
        if (expected.isNotEmpty() && retained.isEmpty()) add("The plan would retain no copy.")
        val missingDirectories = requiredDirectories - survivingDirectories
        if (missingDirectories.isNotEmpty()) add("Required collections lose their representatives: ${missingDirectories.sortedBy { it.toString() }}.")
        for (advice in proposal.files.values) {
            val replacement = advice.retainedReplacementPath
            if (advice.role != FileRecommendationRole.REDUNDANT_CANDIDATE) {
                if (replacement != null) add("A preserved file cannot depend on another keeper: ${advice.file.path}.")
                continue
            }
            if (advice.file.path in protectedPaths) add("A protected file is marked redundant: ${advice.file.path}.")
            val keeper = replacement?.let { proposal.files[it] }
            if (replacement == advice.file.path || keeper == null ||
                keeper.role !in setOf(FileRecommendationRole.PREFERRED, FileRecommendationRole.PRESERVE)) {
                add("Redundant candidate ${advice.file.path} lacks a different, directly retained replacement in this group.")
            }
            if (advice.retentionDecisionConfidence.score < 75) {
                add("Redundant candidate ${advice.file.path} lacks high per-file decision confidence.")
            }
        }
        when (proposal.action) {
            RecommendationAction.KEEP_ONE ->
                if (retained.size != 1 || retained.singleOrNull()?.role != FileRecommendationRole.PREFERRED)
                    add("KEEP_ONE must designate exactly one retained preferred file.")
            RecommendationAction.KEEP_MULTIPLE ->
                if (retained.size < 2 || retained.any { it.role == FileRecommendationRole.PREFERRED })
                    add("KEEP_MULTIPLE must retain multiple representatives without claiming a single preferred file.")
            RecommendationAction.REVIEW, RecommendationAction.PROTECTED ->
                if (candidates.isNotEmpty()) add("Review/protection cannot mark files redundant.")
        }
    }
    if (problems.isEmpty()) return proposal

    val explanation = "Unsafe retention plan rejected; preserve every file for review. ${problems.joinToString(" ")}"
    val confidence = ConfidenceAssessment(0, setOf(ConfidenceReason.RETENTION_PLAN_INVALID), explanation)
    return proposal.copy(
        action = RecommendationAction.REVIEW,
        evidenceStrength = EvidenceStrength.INSUFFICIENT,
        reasonCodes = proposal.reasonCodes + RecommendationReason.RETENTION_PLAN_INVALID,
        explanation = "$explanation Metadata is not content verification; no files are changed.",
        files = expected.mapValues { (_, file) ->
            FileRecommendation(file, FileRecommendationRole.REVIEW, normalizedRecommendationFilename(file.name),
                setOf(RecommendationReason.RETENTION_PLAN_INVALID), explanation, confidence)
        },
        duplicateConfidence = if (changedInputs) DuplicateConfidenceAssessment(null, ConfidenceAssessment(0,
            setOf(ConfidenceReason.EVIDENCE_UPDATING),
            "Group membership or input metadata changed; reassess duplicate evidence before using this plan."), emptyMap())
        else proposal.duplicateConfidence,
    )
}
