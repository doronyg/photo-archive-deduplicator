package com.doronyg.photoarchivededuplicator.operations

import com.doronyg.photoarchivededuplicator.recommendation.*
import com.doronyg.photoarchivededuplicator.strings.AppStrings
import kotlinx.serialization.json.*

internal fun duplicateDeletionGroupId(selection: DuplicateDeletionSelection): String =
    selection.source.name + selection.files.map { it.path.toString() }.sorted()
        .joinToString("") { "${it.length}:$it" }

private fun invalidSelection(code: FileOperationIssueCode, detail: String): Nothing =
    throw FileOperationException(FileOperationFinding(code, detail = detail))

private fun normalizedSelection(selection: DuplicateDeletionSelection): DuplicateDeletionSelection {
    val files = selection.files.sortedBy { it.path.toString() }
    if (files.size < 2 || files.map { it.path }.distinct().size != files.size)
        invalidSelection(FileOperationIssueCode.STALE_RECOMMENDATION, "Select a complete current duplicate group.")
    selection.selectedPaths?.let { paths ->
        if (paths.isEmpty())
            invalidSelection(FileOperationIssueCode.EMPTY_SELECTION, "Select at least one file.")
        if (!files.map { it.path }.containsAll(paths))
            invalidSelection(FileOperationIssueCode.STALE_RECOMMENDATION, "Selected files are not members of the original group.")
        if (paths.size == files.size)
            invalidSelection(FileOperationIssueCode.SURVIVOR_SELECTED, "At least one original group file must remain unselected.")
    }
    return selection.copy(files = files, selectedPaths = selection.selectedPaths?.sortedBy { it.toString() }?.toSet())
}

/** Explicit file choices win over a group plan, and repeated explicit additions form a union. */
internal fun mergeDuplicateDeletionSelections(
    existing: List<DuplicateDeletionSelection>,
    additions: List<DuplicateDeletionSelection>,
): List<DuplicateDeletionSelection> {
    val merged = linkedMapOf<String, DuplicateDeletionSelection>()
    for (input in existing + additions) {
        val selection = normalizedSelection(input)
        val key = duplicateDeletionGroupId(selection)
        val previous = merged[key]
        if (previous != null && previous.files != selection.files)
            invalidSelection(FileOperationIssueCode.STALE_RECOMMENDATION, "The duplicate group inventory changed.")
        merged[key] = normalizedSelection(when {
            previous == null -> selection
            previous.selectedPaths == null -> selection
            selection.selectedPaths == null -> previous
            else -> selection.copy(selectedPaths = previous.selectedPaths + selection.selectedPaths)
        })
    }
    return merged.values.toList()
}

internal fun mergeSelectedDeletionGroups(
    existing: List<SelectedDeletionGroup>,
    additions: List<SelectedDeletionGroup>,
): List<SelectedDeletionGroup> = linkedMapOf<String, SelectedDeletionGroup>().apply {
    (existing + additions).forEach { put(it.key, it) }
}.values.toList()

internal fun duplicateDeletionGroup(
    input: DuplicateDeletionSelection,
    snapshot: DeletionAnalysisSnapshot,
): SelectedDeletionGroup {
    val selection = normalizedSelection(input)
    if (snapshot.source != selection.source || snapshot.files.sortedBy { it.path.toString() } != selection.files ||
        snapshot.recommendation.files.values.map { it.file }.sortedBy { it.path.toString() } != selection.files)
        invalidSelection(FileOperationIssueCode.STALE_RECOMMENDATION, "The original duplicate group is no longer current.")
    val engine = snapshot.recommendation
    if (snapshot.hasProtectedMember() || engine.action == RecommendationAction.PROTECTED)
        invalidSelection(FileOperationIssueCode.PROTECTED_GROUP, "The duplicate group contains a protected directory.")
    val selected = selection.selectedPaths
    val advice = engine.files.values.sortedBy { it.file.path.toString() }
    val retained = advice.filter { selected == null && it.role != FileRecommendationRole.REDUNDANT_CANDIDATE ||
        selected != null && it.file.path !in selected }
    val retainedPaths = retained.mapTo(mutableSetOf()) { it.file.path }
    val manualKeeper = retained.firstOrNull {
        it.role == FileRecommendationRole.PREFERRED || it.role == FileRecommendationRole.PRESERVE
    } ?: retained.firstOrNull()
    val plan = if (selected == null) advice else advice.map {
        if (it.file.path in selected) it.copy(role = FileRecommendationRole.REDUNDANT_CANDIDATE,
            retainedReplacementPath = manualKeeper?.file?.path,
            explanation = "${AppStrings.operations.manualSelectionReason}\n${it.explanation}")
        else if (it.role == FileRecommendationRole.REDUNDANT_CANDIDATE)
            it.copy(role = FileRecommendationRole.PRESERVE, retainedReplacementPath = null)
        else it
    }
    val candidates = plan.filter { it.role == FileRecommendationRole.REDUNDANT_CANDIDATE }
    if (retainedPaths.isEmpty() ||
        candidates.any { it.retainedReplacementPath !in retainedPaths })
        invalidSelection(FileOperationIssueCode.EMPTY_SELECTION, "The engine has no usable candidate plan with retained replacements.")
    val recommendation = engine.copy(files = plan.associateBy { it.file.path })
    fun present(item: FileRecommendation) = DeletionReviewFile(item, snapshot.exif[item.file.path],
        snapshot.retention[item.file.path], snapshot.directoryClassifications[item.file.directoryPath])
    val category = DeletionRecommendationCategory("duplicate-selection-${selection.source.name.lowercase()}",
        if (selected != null) AppStrings.operations.manualDeletionCategory
        else if (selection.source == DeletionRecommendationSource.REGULAR) AppStrings.operations.duplicateGroupCategory
        else AppStrings.operations.suspectedGroupCategory, AppStrings.operations.duplicateSelectionDescription)
    val evidence = buildJsonObject {
        put("action", engine.action.name)
        put("strength", engine.evidenceStrength.name)
        put("reasons", JsonArray(engine.reasonCodes.map { JsonPrimitive(it.name) }))
        put("explanation", engine.explanation)
        put("confidence", engine.duplicateConfidence.score)
        put("confidenceExplanation", engine.duplicateConfidence.assessment.explanation)
        put("confidenceReasons", JsonArray(engine.duplicateConfidence.assessment.reasonCodes.map { JsonPrimitive(it.name) }))
        put("reference", engine.duplicateConfidence.referenceFile?.path?.toString())
        put("comparisons", buildJsonArray {
            engine.duplicateConfidence.comparisons.values.sortedBy { it.file.path.toString() }.forEach { comparison ->
                add(buildJsonObject {
                    put("path", comparison.file.path.toString())
                    put("reference", comparison.reference.path.toString())
                    put("score", comparison.assessment.score)
                    put("explanation", comparison.assessment.explanation)
                    put("reasons", JsonArray(comparison.assessment.reasonCodes.map { JsonPrimitive(it.name) }))
                    put("contradictions", comparison.hasContradictions)
                    put("evidence", buildJsonArray {
                        comparison.evidence.forEach { item -> add(buildJsonObject {
                            put("reason", item.reasonCode.name)
                            put("points", item.points)
                            put("explanation", item.explanation)
                        }) }
                    })
                })
            }
        })
        put("members", buildJsonArray {
            advice.forEach { item -> add(buildJsonObject {
                put("path", item.file.path.toString())
                put("role", item.role.name)
                put("normalizedFilename", item.normalizedFilename)
                put("replacement", item.retainedReplacementPath?.toString())
                put("explanation", item.explanation)
                put("decisionConfidence", item.retentionDecisionConfidence.score)
                put("evidence", operationEvidence(item, snapshot.exif[item.file.path],
                    snapshot.retention[item.file.path]?.score, snapshot.directoryClassifications[item.file.directoryPath]))
            }) }
        })
    }
    return SelectedDeletionGroup(category, DeletionRecommendationGroup(duplicateDeletionGroupId(selection),
        AppStrings.operations.duplicateSelectionEvidence +
            (if (selected == null && candidates.isEmpty()) "\n${AppStrings.operations.groupKeepsAll}" else "") +
            (if (selected != null) "\n${AppStrings.operations.manualSelectionReason}" else ""),
        plan.filter { it.role != FileRecommendationRole.REDUNDANT_CANDIDATE }.map(::present),
        candidates.map(::present), recommendation), DuplicateDeletionProvenance(selection, evidence))
}
