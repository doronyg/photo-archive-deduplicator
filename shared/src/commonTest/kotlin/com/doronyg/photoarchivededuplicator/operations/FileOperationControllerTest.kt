package com.doronyg.photoarchivededuplicator.operations

import com.doronyg.photoarchivededuplicator.retention.RetentionScoreService
import com.doronyg.photoarchivededuplicator.scan.concurrencyTest
import com.doronyg.photoarchivededuplicator.scan.createFilesDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import okio.Path.Companion.toPath
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class FileOperationControllerTest {
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
            executeMoves = { approved, progress ->
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
