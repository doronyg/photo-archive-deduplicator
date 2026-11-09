package com.doronyg.photoarchivededuplicator.operations

import com.doronyg.photoarchivededuplicator.retention.RetentionScoreService
import com.doronyg.photoarchivededuplicator.scan.concurrencyTest
import com.doronyg.photoarchivededuplicator.scan.createFilesDatabase
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import com.doronyg.photoarchivededuplicator.recommendation.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import okio.Path.Companion.toPath
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class FileOperationControllerTest {
    @Test
    fun cancellationIsCooperativeAndKeepsPartialResultsAndPhaseChanges() = concurrencyTest { f ->
        val proposals = FileOperationRepository(createFilesDatabase(f.driver), f.lane)
        val bin = "/Bin".toPath()
        f.repository.configureBinRoot(bin, 1)
        val moves = listOf("a", "b").map { name ->
            ProposedFileMove("/archive/$name.jpg".toPath(), "archive/$name.jpg".toPath(),
                bin / "archive/$name.jpg", "/archive/keep.jpg".toPath(), 5, emptyList())
        }
        val proposal = FileOperationProposal("cancelled-run", 1, 1, null, bin, emptyList(), moves, emptyList())
        proposals.save(proposal)
        val entered = CompletableDeferred<FileExecutionControl>()
        val finish = CompletableDeferred<Unit>()
        val controller = FileOperationController(this, f.repository, proposals,
            FileOperationService(f.repository, proposals, RetentionScoreService(f.repository, dispatcher = f.dispatcher),
                f.lane, f.fakeFs, f.dispatcher), clock = { 1000 },
            executeMoves = { reviewed, progress, control ->
                progress(FileExecutionProgress(2, phase = FileExecutionPhase.MOVING))
                progress(FileExecutionProgress(2, verified = 1, phase = FileExecutionPhase.SAVING_RESULTS))
                entered.complete(control)
                finish.await()
                assertTrue(control.isCancellationRequested)
                FileExecutionResult(reviewed.id, listOf(
                    FileMoveExecutionResult(moves.first(), FileMoveExecutionStatus.COMPLETED),
                    FileMoveExecutionResult(moves.last(), FileMoveExecutionStatus.NOT_STARTED)), cancelled = true)
            })
        controller.openProposal(proposal.id)
        advanceUntilIdle()
        controller.runRealOperation(proposal.id)
        runCurrent()
        assertTrue(entered.isCompleted)
        assertEquals(FileExecutionPhase.SAVING_RESULTS, controller.state.value.executionProgress?.phase,
            "Phase changes must not be hidden by count-update throttling")
        assertEquals(1, controller.state.value.executionProgress?.verified)
        assertEquals(0, controller.state.value.executionProgress?.completed)
        controller.cancelOperation()
        controller.cancelOperation()
        assertTrue(entered.await().isCancellationRequested)
        assertTrue(controller.state.value.cancellationRequested)
        assertTrue(controller.state.value.busy, "Cancelling must wait for safe finalization")
        assertNull(controller.state.value.executionResult)
        finish.complete(Unit)
        advanceUntilIdle()
        assertFalse(controller.state.value.busy)
        assertFalse(controller.state.value.cancellationRequested)
        assertNull(controller.state.value.error)
        assertTrue(controller.state.value.executionResult?.cancelled == true)
        assertEquals(FileExecutionPhase.CANCELLED, controller.state.value.executionProgress?.phase)
        assertEquals(1, controller.state.value.executionProgress?.completed)
        assertEquals(5L, controller.state.value.executionProgress?.logicalBytesMoved)
        val finalState = controller.state.value
        controller.cancelOperation()
        assertEquals(finalState, controller.state.value, "Cancellation after completion must be harmless")
    }

    @Test
    fun partiallyValidDryRunExecutesEligibleGroupsButGlobalErrorsStillBlock() = concurrencyTest { f ->
        val proposals = FileOperationRepository(createFilesDatabase(f.driver), f.lane)
        val bin = "/Bin".toPath()
        f.repository.configureBinRoot(bin, 1)
        val moves = listOf("bad", "good").map { name ->
            ProposedFileMove("/archive/$name.jpg".toPath(), "archive/$name.jpg".toPath(),
                bin / "archive/$name.jpg", "/archive/keep.jpg".toPath(), 5, emptyList())
        }
        val error = FileOperationFinding(FileOperationIssueCode.SURVIVOR_MISSING, "/archive/missing.jpg".toPath())
        val groups = moves.mapIndexed { index, move ->
            val file = FileRecord(move.sourcePath, "/archive".toPath(), move.sourcePath.name, "jpg", 5, 1, 2)
            FileOperationGroup("group-$index", "category", "Category", "Saved evidence", 90,
                listOf(FileOperationMember(file, FileRecommendationRole.REDUNDANT_CANDIDATE, "Candidate", 90,
                    move.survivorPath, null, null, buildJsonObject {})),
                if (index == 0) listOf(error) else emptyList())
        }
        val proposal = FileOperationProposal("partial", 1, 1, null, bin, groups, moves, emptyList())
        proposals.save(proposal)
        assertEquals(proposal, proposals.load(proposal.id))
        var calls = 0
        val controller = FileOperationController(this, f.repository, proposals,
            FileOperationService(f.repository, proposals, RetentionScoreService(f.repository, dispatcher = f.dispatcher),
                f.lane, f.fakeFs, f.dispatcher),
            executeMoves = { reviewed, _, _ ->
                calls++
                assertEquals(listOf(moves.last()), reviewed.executionEligibility.moves)
                FileExecutionResult(reviewed.id, listOf(
                    FileMoveExecutionResult(moves.first(), FileMoveExecutionStatus.FAILED, "Invalid group"),
                    FileMoveExecutionResult(moves.last(), FileMoveExecutionStatus.COMPLETED)))
            })
        controller.openProposal(proposal.id)
        advanceUntilIdle()
        controller.runRealOperation(proposal.id)
        advanceUntilIdle()
        assertNull(controller.state.value.error)
        assertEquals(1, calls)
        assertEquals(1, controller.state.value.executionProgress?.completed)
        assertEquals(1, controller.state.value.executionProgress?.failed)
        assertEquals(5L, controller.state.value.executionProgress?.logicalBytesMoved)
        val global = proposal.copy(id = "global", findings = listOf(FileOperationFinding(FileOperationIssueCode.BIN_ROOT_CHANGED)))
        proposals.save(global)
        controller.openProposal(global.id)
        advanceUntilIdle()
        controller.runRealOperation(global.id)
        advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals(FileOperationIssueCode.EXECUTION_ERROR, controller.state.value.error?.code)
    }

    @Test
    fun bulkSelectionHandlesTwentyThousandGroupsWithoutWritingOrApprovingMoves() = concurrencyTest { f ->
        val proposals = FileOperationRepository(createFilesDatabase(f.driver), f.lane)
        val service = FileOperationService(f.repository, proposals,
            RetentionScoreService(f.repository, dispatcher = f.dispatcher), f.lane, f.fakeFs, f.dispatcher)
        val controller = FileOperationController(this, f.repository, proposals, service)
        val files = listOf("a.jpg", "b.jpg").map {
            FileRecord("/photos/$it".toPath(), "/photos".toPath(), it, "jpg", 123, 1, 2)
        }
        val advice = DuplicateRecommendationEngine().recommend(files, emptyMap(), emptyMap())
        val members = advice.files.values.map { DeletionReviewFile(it, null, null, null) }
        val category = DeletionRecommendationCategory("category", "Category", "Description")
        val groups = List(20_000) {
            SelectedDeletionGroup(category,
                DeletionRecommendationGroup("group-$it", "Evidence", members.take(1), members.drop(1), advice))
        }
        val queries = f.driver.statements.size
        controller.selectGroups(groups + groups.first())
        assertEquals(20_000, controller.state.value.availableGroups.size)
        controller.clearGroupSelection()
        assertTrue(controller.state.value.selectedGroups.isEmpty())
        assertEquals(20_000, controller.state.value.availableGroups.size)
        controller.setGroupSelected(groups.last().key, true)
        assertEquals(listOf(groups.last()), controller.state.value.selectedGroups)
        controller.selectAllGroups()
        assertEquals(groups, controller.state.value.selectedGroups)
        assertSame(groups.first(), controller.state.value.selectedGroups.first())
        controller.setGroupSelected(groups.first().key, false)
        assertEquals(19_999, controller.state.value.selectedGroups.size)
        controller.setGroupSelected("unknown", true)
        assertEquals(FileOperationIssueCode.STALE_RECOMMENDATION, controller.state.value.error?.code)
        assertNull(controller.state.value.proposal)
        assertNull(controller.state.value.executionResult)
        assertEquals(queries, f.driver.statements.size, "Selection must not query or write the database")
        controller.refresh()
        controller.clearGroupSelection()
        assertEquals(FileOperationIssueCode.BUSY, controller.state.value.error?.code)
        assertEquals(19_999, controller.state.value.selectedGroups.size)
        advanceUntilIdle()
        controller.clearGroupSelection()
        controller.selectAllGroups()
        assertEquals(groups, controller.state.value.selectedGroups)
        val saved = FileOperationProposal("saved-selection", 1, 1, null, "/Bin".toPath(),
            emptyList(), emptyList(), emptyList())
        proposals.save(saved)
        controller.openProposal(saved.id)
        advanceUntilIdle()
        assertEquals(saved, controller.state.value.proposal)
        controller.clearGroupSelection()
        assertNull(controller.state.value.proposal)
        assertEquals(saved, proposals.load(saved.id), "Changing selection must not edit a saved dry run")
        controller.selectAllGroups()
        assertEquals(groups, controller.state.value.selectedGroups)
    }

    @Test
    fun realRunRequiresExplicitCurrentProposalAndReportsExecutionProgressAndResult() = concurrencyTest { f ->
        val proposals = FileOperationRepository(createFilesDatabase(f.driver), f.lane)
        val bin = "/Bin".toPath()
        f.repository.configureBinRoot(bin, 1)
        val proposal = FileOperationProposal("approved-real-run", 1, 1, null, bin, emptyList(),
            listOf(ProposedFileMove("/archive/a.jpg".toPath(), "archive/a.jpg".toPath(),
                bin / "archive/a.jpg", "/archive/b.jpg".toPath(), 1, emptyList())), emptyList())
        proposals.save(proposal)
        val service = FileOperationService(f.repository, proposals,
            RetentionScoreService(f.repository, dispatcher = f.dispatcher), f.lane, f.fakeFs, f.dispatcher)
        var calls = 0
        val result = FileExecutionResult(proposal.id, listOf(
            FileMoveExecutionResult(proposal.moves.single(), FileMoveExecutionStatus.COMPLETED)))
        val controller = FileOperationController(this, f.repository, proposals, service,
            executeMoves = { approved, progress, _ ->
                calls++
                assertEquals(proposal, approved)
                assertFailsWith<IllegalStateException> { f.repository.configureBinRoot("/Other".toPath(), 2) }
                progress(FileExecutionProgress(total = 1, completed = 1, finished = true))
                result
            })
        controller.openProposal(proposal.id)
        advanceUntilIdle()
        controller.runRealOperation("old-approval-dialog")
        advanceUntilIdle()
        assertEquals(0, calls)
        assertEquals(FileOperationIssueCode.EXECUTION_ERROR, controller.state.value.error?.code)
        controller.runRealOperation(proposal.id)
        advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals(result, controller.state.value.executionResult)
        assertEquals(1, controller.state.value.executionProgress?.completed)
        assertEquals(1L, controller.state.value.executionProgress?.logicalBytesMoved)
        assertTrue(controller.state.value.executionProgress?.finished == true)
        assertFalse(controller.state.value.busy)
        controller.runRealOperation(proposal.id)
        advanceUntilIdle()
        assertEquals(1, calls)
    }

    @Test
    fun directoryPreparationRequiresTheDisplayedPassedProposalAndCurrentBin() = concurrencyTest { f ->
        val proposals = FileOperationRepository(createFilesDatabase(f.driver), f.lane)
        val bin = "/Bin".toPath()
        f.repository.configureBinRoot(bin, 1)
        val proposal = FileOperationProposal("passed", 1, 1, null, bin, emptyList(),
            listOf(ProposedFileMove("/archive/a.jpg".toPath(), "archive/a.jpg".toPath(),
                bin / "archive/a.jpg", "/archive/b.jpg".toPath(), 1, emptyList())), emptyList())
        proposals.save(proposal)
        val service = FileOperationService(f.repository, proposals,
            RetentionScoreService(f.repository, dispatcher = f.dispatcher), f.lane, f.fakeFs, f.dispatcher)
        var calls = 0
        val controller = FileOperationController(this, f.repository, proposals, service,
            prepareBinDirectories = {
                calls++
                assertFailsWith<IllegalStateException> { f.repository.configureBinRoot("/Other".toPath(), 2) }
                BinDirectoryPreparationResult(2, 3, 3)
            })
        controller.openProposal(proposal.id)
        advanceUntilIdle()
        controller.prepareDirectories("outdated-dialog")
        advanceUntilIdle()
        assertEquals(0, calls)
        assertEquals(FileOperationIssueCode.DIRECTORY_PREPARATION_ERROR, controller.state.value.error?.code)
        controller.prepareDirectories(proposal.id)
        advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals(BinDirectoryPreparationResult(2, 3, 3), controller.state.value.directoryPreparation)
        f.repository.configureBinRoot("/Changed".toPath(), 3)
        controller.prepareDirectories(proposal.id)
        advanceUntilIdle()
        assertEquals(1, calls)
        assertNull(controller.state.value.directoryPreparation)
        assertEquals(FileOperationIssueCode.DIRECTORY_PREPARATION_ERROR, controller.state.value.error?.code)
    }

    @Test
    fun configurationRefreshAndValidationFailuresAreVisibleInAppOwnedState() = concurrencyTest { f ->
        val proposals = FileOperationRepository(createFilesDatabase(f.driver), f.lane)
        val service = FileOperationService(f.repository, proposals,
            RetentionScoreService(f.repository, dispatcher = f.dispatcher), f.lane,
            f.fakeFs, f.dispatcher)
        val controller = FileOperationController(this, f.repository, proposals, service)
        controller.refresh()
        advanceUntilIdle()
        assertNull(controller.state.value.binRoot)
        assertTrue(controller.state.value.history.isEmpty())
        controller.configureBin("/DeduplicatorGarbageBin")
        advanceUntilIdle()
        assertEquals("/DeduplicatorGarbageBin".toPath(), controller.state.value.binRoot)
        assertFalse(f.fakeFs.exists("/DeduplicatorGarbageBin".toPath()))
        controller.createDryRun()
        advanceUntilIdle()
        assertEquals(FileOperationIssueCode.EMPTY_SELECTION, controller.state.value.error?.code)
        assertFalse(controller.state.value.busy)
        controller.configureBin("relative/path")
        advanceUntilIdle()
        assertEquals(FileOperationIssueCode.CONFIGURATION_ERROR, controller.state.value.error?.code)
        assertEquals("/DeduplicatorGarbageBin".toPath(), f.repository.binRoot())
    }

    @Test
    fun activeWorkBlocksConfigurationAndDryRunButNotHistoryReads() = concurrencyTest { f ->
        val proposals = FileOperationRepository(createFilesDatabase(f.driver), f.lane)
        val service = FileOperationService(f.repository, proposals,
            RetentionScoreService(f.repository, dispatcher = f.dispatcher), f.lane,
            f.fakeFs, f.dispatcher)
        val controller = FileOperationController(this, f.repository, proposals, service, admissionBlocked = { true })
        controller.configureBin("/DeduplicatorGarbageBin")
        advanceUntilIdle()
        assertEquals(FileOperationIssueCode.BUSY, controller.state.value.error?.code)
        assertNull(f.repository.binRoot())
        controller.refresh()
        advanceUntilIdle()
        assertNull(controller.state.value.error)
        assertTrue(controller.state.value.history.isEmpty())
    }
}
