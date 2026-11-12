package com.doronyg.photoarchivededuplicator.operations

import com.doronyg.photoarchivededuplicator.classification.DirectoryClassification
import com.doronyg.photoarchivededuplicator.classification.DirectoryClassificationRecord
import com.doronyg.photoarchivededuplicator.classification.OllamaSettings
import com.doronyg.photoarchivededuplicator.exif.ExifInput
import com.doronyg.photoarchivededuplicator.exif.ExifMetadata
import com.doronyg.photoarchivededuplicator.exif.ExifReadResult
import com.doronyg.photoarchivededuplicator.exif.ExifRecord
import com.doronyg.photoarchivededuplicator.exif.ExifStatus
import com.doronyg.photoarchivededuplicator.recommendation.DeletionAnalysisSnapshot
import com.doronyg.photoarchivededuplicator.recommendation.DeletionRecommendationSource
import com.doronyg.photoarchivededuplicator.recommendation.DuplicateRecommendationEngine
import com.doronyg.photoarchivededuplicator.recommendation.FileRecommendationRole
import com.doronyg.photoarchivededuplicator.recommendation.RecommendationAction
import com.doronyg.photoarchivededuplicator.retention.RetentionPresentation
import com.doronyg.photoarchivededuplicator.retention.RetentionRank
import com.doronyg.photoarchivededuplicator.retention.RetentionScorer
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import com.doronyg.photoarchivededuplicator.strings.AppStrings
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DuplicateDeletionSelectionsTest {
    private fun file(name: String, directory: String = "/photos/Family") =
        FileRecord("$directory/$name".toPath(), directory.toPath(), name, "jpg", 120_001, 1, 2, 3)

    private fun snapshot(files: List<FileRecord>, withEvidence: Boolean = false): DeletionAnalysisSnapshot {
        val exif = if (withEvidence) files.associate { it.path to ExifRecord(ExifInput.from(it),
            ExifReadResult(ExifStatus.EXTRACTED, ExifMetadata(capturedAt = "2023:02:18 12:44:25")), 4) }
            else emptyMap()
        val scores = if (withEvidence) files.associate { it.path to RetentionPresentation(
            RetentionScorer().score(it, 5, exif[it.path]), RetentionRank.CLOSE) } else emptyMap()
        return DeletionAnalysisSnapshot(files, DuplicateRecommendationEngine().recommend(files, exif, scores),
            exif, scores, emptyMap(), DeletionRecommendationSource.SUSPECTED)
    }

    private fun selection(snapshot: DeletionAnalysisSnapshot, paths: Set<okio.Path>? = null) =
        DuplicateDeletionSelection(snapshot.source, snapshot.files, paths)

    @Test
    fun manualSelectionOverridesReviewWithoutInventingConfidenceAndPreservesUnselectedMembers() {
        val snapshot = snapshot(listOf(file("IMG.JPG"), file("IMG-001.JPG"), file("IMG-002.JPG")))
        assertEquals(RecommendationAction.REVIEW, snapshot.recommendation.action)
        val result = duplicateDeletionGroup(selection(snapshot, setOf(snapshot.files.first().path)), snapshot)
        val plan = result.group.recommendation
        assertEquals(snapshot.recommendation.duplicateConfidence, plan.duplicateConfidence)
        assertEquals(listOf(snapshot.files.first()), plan.redundantCandidates)
        assertEquals(snapshot.files.drop(1).toSet(), plan.mustPreserve.toSet())
        val candidate = plan.files.getValue(snapshot.files.first().path)
        assertEquals(snapshot.files[1].path, candidate.retainedReplacementPath)
        assertEquals(snapshot.recommendation.files.getValue(candidate.file.path).retentionDecisionConfidence,
            candidate.retentionDecisionConfidence)
        assertTrue(candidate.explanation.contains(AppStrings.operations.manualSelectionReason))
        assertNotNull(result.duplicateProvenance)
    }

    @Test
    fun explicitEngineKeeperSelectionRetainsFormerCandidateAndStableIdentity() {
        val snapshot = snapshot(listOf(file("IMG.JPG"), file("IMG-001.JPG"), file("IMG-002.JPG")), true)
        assertEquals(RecommendationAction.KEEP_ONE, snapshot.recommendation.action)
        val keeper = snapshot.recommendation.preferredFile!!
        val manual = duplicateDeletionGroup(selection(snapshot, setOf(keeper.path)), snapshot)
        val group = duplicateDeletionGroup(selection(snapshot), snapshot)
        assertEquals(group.key, manual.key)
        assertEquals(listOf(keeper), manual.group.recommendation.redundantCandidates)
        assertTrue(manual.group.survivors.all { it.advice.role == FileRecommendationRole.PRESERVE })
        assertEquals(snapshot.files.filter { it.path != keeper.path }.map { it.path }.minBy { it.toString() },
            manual.group.candidates.single().advice.retainedReplacementPath)
        assertEquals(duplicateDeletionGroupId(selection(snapshot)),
            duplicateDeletionGroupId(selection(snapshot).copy(files = snapshot.files.reversed())))
    }

    @Test
    fun groupPlanKeepsMultipleCollectionRepresentativesAndOnlyEngineCandidates() {
        val files = listOf(file("IMG.JPG"), file("IMG-001.JPG"),
            file("IMG.JPG", "/photos/Trips"), file("IMG-001.JPG", "/photos/Trips"))
        val snapshot = snapshot(files, true)
        assertEquals(RecommendationAction.KEEP_MULTIPLE, snapshot.recommendation.action)
        val result = duplicateDeletionGroup(selection(snapshot), snapshot)
        assertEquals(snapshot.recommendation, result.group.recommendation)
        assertEquals(2, result.group.survivors.size)
        assertEquals(2, result.group.candidates.size)
        assertTrue(result.group.candidates.all { candidate ->
            result.group.survivors.any { it.advice.file.path == candidate.advice.retainedReplacementPath }
        })
    }

    @Test
    fun allSelectedEmptyUnknownAndStaleFilesAreRejected() {
        val snapshot = snapshot(listOf(file("IMG.JPG"), file("IMG-001.JPG")))
        assertEquals(FileOperationIssueCode.SURVIVOR_SELECTED, assertFailsWith<FileOperationException> {
            duplicateDeletionGroup(selection(snapshot, snapshot.files.map { it.path }.toSet()), snapshot)
        }.finding.code)
        assertEquals(FileOperationIssueCode.EMPTY_SELECTION, assertFailsWith<FileOperationException> {
            duplicateDeletionGroup(selection(snapshot, emptySet()), snapshot)
        }.finding.code)
        assertEquals(FileOperationIssueCode.STALE_RECOMMENDATION, assertFailsWith<FileOperationException> {
            duplicateDeletionGroup(selection(snapshot, setOf("/other.jpg".toPath())), snapshot)
        }.finding.code)
        val reviewed = duplicateDeletionGroup(selection(snapshot), snapshot)
        assertTrue(reviewed.group.candidates.isEmpty())
        assertEquals(snapshot.files.map { it.path }.toSet(),
            reviewed.group.survivors.map { it.advice.file.path }.toSet())
        val stale = selection(snapshot, setOf(snapshot.files.first().path)).copy(
            files = snapshot.files.map { it.copy(lastScanned = 99) })
        assertEquals(FileOperationIssueCode.STALE_RECOMMENDATION, assertFailsWith<FileOperationException> {
            duplicateDeletionGroup(stale, snapshot)
        }.finding.code)
    }

    @Test
    fun protectedGroupsAreBlockedInBothModes() {
        val original = snapshot(listOf(file("IMG.JPG"), file("IMG-001.JPG")), true)
        val folder = original.files.first().directoryPath
        val context = OllamaSettings("http://test", "model").context()
        val snapshot = original.copy(directoryClassifications = mapOf(folder to
            DirectoryClassificationRecord(folder, folder.toString(), DirectoryClassification.PROTECTED, context, 6)))
        for (paths in listOf(null, setOf(snapshot.files.first().path))) {
            assertEquals(FileOperationIssueCode.PROTECTED_GROUP, assertFailsWith<FileOperationException> {
                duplicateDeletionGroup(selection(snapshot, paths), snapshot)
            }.finding.code)
        }
    }

    @Test
    fun repeatedManualSelectionsMergeAndWinModeConflictsWithoutDroppingUnrelatedGroups() {
        val snapshot = snapshot(listOf(file("IMG.JPG"), file("IMG-001.JPG"), file("IMG-002.JPG")))
        val group = selection(snapshot)
        val first = selection(snapshot, setOf(snapshot.files[0].path))
        val second = selection(snapshot, setOf(snapshot.files[1].path))
        val unrelated = first.copy(source = DeletionRecommendationSource.REGULAR)
        val merged = mergeDuplicateDeletionSelections(listOf(group, unrelated), listOf(first, second, group))
        assertEquals(listOf(first.source, unrelated.source), merged.map { it.source })
        assertEquals(first.selectedPaths!! + second.selectedPaths!!, merged.first().selectedPaths)
        assertEquals(unrelated.selectedPaths, merged.last().selectedPaths)
        assertEquals(FileOperationIssueCode.SURVIVOR_SELECTED, assertFailsWith<FileOperationException> {
            mergeDuplicateDeletionSelections(merged, listOf(selection(snapshot, setOf(snapshot.files[2].path))))
        }.finding.code)
        val a = duplicateDeletionGroup(first, snapshot)
        val b = duplicateDeletionGroup(second, snapshot)
        val other = a.copy(category = a.category.copy(id = "unrelated"))
        assertEquals(listOf(b, other), mergeSelectedDeletionGroups(listOf(a, other), listOf(b)))
    }
}
