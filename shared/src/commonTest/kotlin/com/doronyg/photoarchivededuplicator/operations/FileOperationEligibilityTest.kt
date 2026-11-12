package com.doronyg.photoarchivededuplicator.operations

import com.doronyg.photoarchivededuplicator.recommendation.FileRecommendationRole
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import kotlinx.serialization.json.buildJsonObject
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FileOperationEligibilityTest {
    private fun group(key: String, vararg names: String, findings: List<FileOperationFinding> = emptyList()) =
        FileOperationGroup(key, "category", "Category", "Saved evidence", 90, names.map { name ->
            val path = "/archive/$name.jpg".toPath()
            FileOperationMember(FileRecord(path, path.parent!!, path.name, "jpg", 5, 1, 2),
                FileRecommendationRole.REDUNDANT_CANDIDATE, "Candidate", 90,
                "/archive/keep.jpg".toPath(), null, null, buildJsonObject {})
        }, findings)

    private fun proposal(groups: List<FileOperationGroup>, names: List<String>) = FileOperationProposal(
        "operation", 1, 1, null, "/bin".toPath(), groups,
        names.map { name -> ProposedFileMove("/archive/$name.jpg".toPath(), "archive/$name.jpg".toPath(),
            "/bin/archive/$name.jpg".toPath(), "/archive/keep.jpg".toPath(), 5, emptyList()) },
        emptyList())

    @Test
    fun groupErrorsDoNotBlockIndependentGroups() {
        val error = FileOperationFinding(FileOperationIssueCode.SURVIVOR_MISSING, "/archive/missing.jpg".toPath())
        val proposal = proposal(listOf(group("bad", "a", "b", findings = listOf(error)), group("good", "c")),
            listOf("a", "b", "c"))
        assertEquals(FileOperationValidationStatus.PARTIALLY_READY, proposal.validationStatus)
        assertTrue(proposal.canExecute)
        assertEquals(listOf(proposal.moves.last()), proposal.executionEligibility.moves)
        assertEquals(setOf(proposal.moves[0].sourcePath, proposal.moves[1].sourcePath),
            proposal.executionEligibility.rejected.keys)
        assertTrue(proposal.executionEligibility.rejected.values.all { error in it })
    }

    @Test
    fun aMoveErrorRejectsItsWholeGroupAndSharedCandidateDependencies() {
        val base = proposal(listOf(group("first", "a", "b"), group("overlap", "b", "c"), group("good", "d")),
            listOf("a", "b", "c", "d"))
        val error = FileOperationFinding(FileOperationIssueCode.DESTINATION_EXISTS, base.moves.first().destinationPath)
        val proposal = base.copy(moves = base.moves.mapIndexed { index, move ->
            if (index == 0) move.copy(findings = listOf(error)) else move
        })
        assertEquals(listOf(proposal.moves.last()), proposal.executionEligibility.moves)
        assertEquals(3, proposal.executionEligibility.rejected.size)
        assertEquals(FileOperationValidationStatus.PARTIALLY_READY, proposal.validationStatus)
    }

    @Test
    fun globalErrorsAndAllInvalidGroupsDisableExecution() {
        val base = proposal(listOf(group("good", "a")), listOf("a"))
        assertEquals(FileOperationValidationStatus.READY, base.validationStatus)
        val error = FileOperationFinding(FileOperationIssueCode.BIN_ROOT_CHANGED)
        val global = base.copy(findings = listOf(error))
        assertFalse(global.canExecute)
        assertEquals(FileOperationValidationStatus.NEEDS_ATTENTION, global.validationStatus)
        assertTrue(global.executionEligibility.moves.isEmpty())
        assertEquals(listOf(error), global.executionEligibility.rejected.getValue(base.moves.single().sourcePath))
        val groupFailure = base.copy(groups = listOf(group("bad", "a", findings = listOf(error))))
        assertFalse(groupFailure.canExecute)
        assertEquals(FileOperationValidationStatus.NEEDS_ATTENTION, groupFailure.validationStatus)
    }

    @Test
    fun eligibilityHandlesTwentyThousandIndependentGroups() {
        val error = FileOperationFinding(FileOperationIssueCode.PROTECTED_GROUP)
        val proposal = proposal(List(20_000) {
            group("group-$it", "file-$it", findings = if (it % 100 == 0) listOf(error) else emptyList())
        }, List(20_000) { "file-$it" })
        assertEquals(19_800, proposal.executionEligibility.moves.size)
        assertEquals(200, proposal.executionEligibility.rejected.size)
        assertSame(proposal.executionEligibility, proposal.executionEligibility)
    }
}
