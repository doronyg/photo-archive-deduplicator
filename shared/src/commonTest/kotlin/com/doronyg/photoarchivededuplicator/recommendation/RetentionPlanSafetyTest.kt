package com.doronyg.photoarchivededuplicator.recommendation

import com.doronyg.photoarchivededuplicator.exif.ExifInput
import com.doronyg.photoarchivededuplicator.exif.ExifMetadata
import com.doronyg.photoarchivededuplicator.exif.ExifReadResult
import com.doronyg.photoarchivededuplicator.exif.ExifRecord
import com.doronyg.photoarchivededuplicator.exif.ExifStatus
import com.doronyg.photoarchivededuplicator.retention.RetentionPresentation
import com.doronyg.photoarchivededuplicator.retention.RetentionRank
import com.doronyg.photoarchivededuplicator.retention.RetentionScorer
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RetentionPlanSafetyTest {
    private val scorer = RetentionScorer()
    private val engine = DuplicateRecommendationEngine(scorer)
    private val capture = "2023:02:18 12:44:25"

    private fun file(name: String, directory: String = "/Photos/2023-02-18", modified: Long = 1): FileRecord {
        val folder = directory.toPath()
        return FileRecord(folder / name, folder, name, "jpg", 120_000, modified, 2, 3)
    }

    private fun records(files: List<FileRecord>, complete: Boolean = true) = files.associate { file ->
        file.path to ExifRecord(ExifInput.from(file), ExifReadResult(ExifStatus.EXTRACTED,
            if (complete) ExifMetadata(capturedAt = capture, make = "Canon", model = "Camera", width = 4000, height = 3000)
            else ExifMetadata(capturedAt = capture)), 4)
    }

    private fun scores(files: List<FileRecord>, exif: Map<Path, ExifRecord>) =
        files.associate { it.path to RetentionPresentation(scorer.score(it, 5, exif[it.path]), RetentionRank.CLOSE) }

    private fun recommend(files: List<FileRecord>, complete: Boolean = true): DuplicateGroupRecommendation {
        val exif = records(files, complete)
        return engine.recommend(files, exif, scores(files, exif))
    }

    private fun assertRejected(result: DuplicateGroupRecommendation, files: List<FileRecord>) {
        assertEquals(RecommendationAction.REVIEW, result.action)
        assertTrue(result.redundantCandidates.isEmpty())
        assertEquals(files.toSet(), result.mustPreserve.toSet())
        assertTrue(RecommendationReason.RETENTION_PLAN_INVALID in result.reasonCodes)
        assertTrue(result.files.values.all {
            it.retentionDecisionConfidence.score == 0 && it.retainedReplacementPath == null
        })
    }

    @Test
    fun allRedundantOrCyclicPlansAreRejectedAndEveryInputIsPreserved() {
        val files = listOf(file("IMG.jpg"), file("IMG-001.jpg"))
        val proposal = recommend(files)
        val unsafe = proposal.copy(files = proposal.files.mapValues { (path, advice) ->
            advice.copy(role = FileRecommendationRole.REDUNDANT_CANDIDATE,
                retainedReplacementPath = files.first { it.path != path }.path)
        })
        assertRejected(enforceRetentionPlanSafety(unsafe, files, setOf(files.first().directoryPath)), files)
    }

    @Test
    fun redundantCandidatesNeedAnExistingNonselfSurvivingReplacement() {
        val files = listOf(file("IMG.jpg"), file("IMG-001.jpg"))
        val proposal = recommend(files)
        val candidate = proposal.files.getValue(files.last().path)
        for (replacement in listOf(null, candidate.file.path, "/outside/IMG.jpg".toPath())) {
            val unsafe = proposal.copy(files = proposal.files + (candidate.file.path to
                candidate.copy(retainedReplacementPath = replacement)))
            assertRejected(enforceRetentionPlanSafety(unsafe, files, setOf(files.first().directoryPath)), files)
        }
    }

    @Test
    fun dependenciesCannotPointToAnotherCandidateEvenWhenSomeOtherKeeperExists() {
        val files = listOf(file("IMG.jpg"), file("IMG-001.jpg"), file("IMG-002.jpg"))
        val proposal = recommend(files)
        val second = proposal.files.getValue(files[1].path).copy(retainedReplacementPath = files[2].path)
        val unsafe = proposal.copy(files = proposal.files + (second.file.path to second))
        assertRejected(enforceRetentionPlanSafety(unsafe, files, setOf(files.first().directoryPath)), files)
    }

    @Test
    fun aMissingMemberCannotDisappearFromTheSafetyPlan() {
        val files = listOf(file("IMG.jpg"), file("IMG-001.jpg"))
        val unsafe = recommend(files).let { it.copy(files = it.files - files.first().path) }
        assertRejected(enforceRetentionPlanSafety(unsafe, files, setOf(files.first().directoryPath)), files)
    }

    @Test
    fun lowConfidenceCandidatesAndContradictoryGroupActionsAreRejected() {
        val files = listOf(file("IMG.jpg"), file("IMG-001.jpg"))
        val proposal = recommend(files)
        val candidate = proposal.files.getValue(files.last().path)
        val lowConfidence = proposal.copy(files = proposal.files + (candidate.file.path to candidate.copy(
            retentionDecisionConfidence = candidate.retentionDecisionConfidence.copy(score = 74))))
        val invalid = listOf(lowConfidence, proposal.copy(action = RecommendationAction.REVIEW),
            proposal.copy(action = RecommendationAction.PROTECTED),
            proposal.copy(action = RecommendationAction.KEEP_MULTIPLE))
        invalid.forEach { assertRejected(enforceRetentionPlanSafety(it, files, setOf(files.first().directoryPath)), files) }
    }

    @Test
    fun preservedFilesCannotCarryDependenciesAndChangedInputMetadataInvalidatesThePlan() {
        val files = listOf(file("IMG.jpg"), file("IMG-001.jpg"))
        val proposal = recommend(files)
        val keeper = proposal.files.getValue(files.first().path)
        val invalid = proposal.copy(files = proposal.files + (keeper.file.path to
            keeper.copy(retainedReplacementPath = files.last().path)))
        assertRejected(enforceRetentionPlanSafety(invalid, files, setOf(files.first().directoryPath)), files)
        val changed = listOf(files.first().copy(modifiedAt = 999), files.last())
        val refreshed = enforceRetentionPlanSafety(proposal, changed, setOf(files.first().directoryPath))
        assertRejected(refreshed, changed)
        assertEquals(0, refreshed.duplicateConfidence.score)
        assertTrue(ConfidenceReason.EVIDENCE_UPDATING in refreshed.duplicateConfidence.assessment.reasonCodes)
    }

    @Test
    fun aRequiredCollectionAndExplicitlyProtectedFileCannotBecomeRedundant() {
        val files = listOf(file("IMG.jpg", "/Photos/Family"), file("IMG.jpg", "/Photos/Zoo"))
        val proposal = recommend(files)
        val zoo = proposal.files.getValue(files.last().path).copy(
            role = FileRecommendationRole.REDUNDANT_CANDIDATE, retainedReplacementPath = files.first().path)
        val unsafe = proposal.copy(action = RecommendationAction.KEEP_ONE, files = proposal.files +
            (files.last().path to zoo) + (files.first().path to proposal.files.getValue(files.first().path).copy(
                role = FileRecommendationRole.PREFERRED)))
        assertRejected(enforceRetentionPlanSafety(unsafe, files, files.map { it.directoryPath }.toSet()), files)
        assertRejected(enforceRetentionPlanSafety(unsafe, files, setOf(files.first().directoryPath),
            protectedPaths = setOf(files.last().path)), files)
    }

    @Test
    fun confidenceIsPerFileAndCandidateConfidenceCannotExceedDuplicateEvidence() {
        val files = listOf(file("IMG.jpg"), file("IMG-001.jpg"))
        val result = recommend(files, complete = false)
        val keeper = result.files.getValue(files.first().path)
        val candidate = result.files.getValue(files.last().path)
        assertEquals(90, keeper.retentionDecisionConfidence.score)
        assertEquals(85, candidate.retentionDecisionConfidence.score)
        assertEquals(keeper.file.path, candidate.retainedReplacementPath)
        assertNull(keeper.retainedReplacementPath)
        assertTrue(ConfidenceReason.DESIGNATED_SURVIVOR in keeper.retentionDecisionConfidence.reasonCodes)
        assertTrue(ConfidenceReason.RETAINED_REPLACEMENT in candidate.retentionDecisionConfidence.reasonCodes)
        assertTrue(candidate.retentionDecisionConfidence.explanation.contains(keeper.file.path.toString()))
    }

    @Test
    fun changingTheKeeperRecomputesEveryDependencyAndNeverRetainsOldAssignments() {
        val files = listOf(file("IMG.jpg"), file("IMG-001.jpg"))
        val exif = records(files)
        val scores = scores(files, exif).toMutableMap()
        val before = engine.recommend(files, exif, scores)
        assertEquals(files.first().path, before.files.getValue(files.last().path).retainedReplacementPath)
        scores[files.last().path] = scores.getValue(files.last().path).let {
            it.copy(score = it.score!!.copy(value = 900.0))
        }
        val after = engine.recommend(files, exif, scores)
        assertEquals(files.last(), after.preferredFile)
        assertEquals(files.last().path, after.files.getValue(files.first().path).retainedReplacementPath)
        assertNull(after.files.getValue(files.last().path).retainedReplacementPath)
    }

    @Test
    fun strongReferenceMatchesCannotSubstituteForEvidenceAgainstTheActualKeeper() {
        val reference = file("IMG.jpg", "/Photos/2023-02-19")
        val partial = file("IMG-001.jpg", "/Photos/2023-02-19")
        val keeper = file("IMG.jpg", modified = 9)
        val files = listOf(reference, partial, keeper)
        val exif = records(files).toMutableMap()
        exif[partial.path] = records(listOf(partial), complete = false).getValue(partial.path)
        val scores = scores(files, exif).toMutableMap()
        scores[reference.path] = scores.getValue(reference.path).let {
            it.copy(score = it.score!!.copy(value = 900.0))
        }
        val result = engine.recommend(files, exif, scores)
        assertEquals(75, result.duplicateConfidence.score)
        assertEquals(RecommendationAction.REVIEW, result.action)
        assertTrue(result.redundantCandidates.isEmpty())
        assertEquals(files.toSet(), result.mustPreserve.toSet())
        assertTrue(result.explanation.contains("60"))
        assertTrue(result.files.values.all { it.retainedReplacementPath == null })
    }

    @Test
    fun aLegitimateMultipleCollectionPlanKeepsEachRepresentativeAndOnlyLocalDependencies() {
        val files = listOf(file("IMG.jpg", "/Photos/Family"), file("IMG-001.jpg", "/Photos/Family"),
            file("IMG.jpg", "/Photos/Zoo"))
        val proposal = recommend(files)
        val result = enforceRetentionPlanSafety(proposal, files, files.map { it.directoryPath }.toSet())
        assertEquals(proposal, result)
        assertEquals(RecommendationAction.KEEP_MULTIPLE, result.action)
        assertEquals(2, result.mustPreserve.size)
        assertEquals(files.first().path, result.files.getValue(files[1].path).retainedReplacementPath)
        assertTrue(result.files.values.filter { it.role != FileRecommendationRole.REDUNDANT_CANDIDATE }
            .all { it.retainedReplacementPath == null })
    }
}
