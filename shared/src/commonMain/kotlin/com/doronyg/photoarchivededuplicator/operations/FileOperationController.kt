package com.doronyg.photoarchivededuplicator.operations

import com.doronyg.photoarchivededuplicator.Diagnostics
import com.doronyg.photoarchivededuplicator.scan.DirectoryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import okio.Path.Companion.toPath
import kotlin.time.Clock

internal class FileOperationController(
    private val scope: CoroutineScope,
    private val repository: DirectoryRepository,
    private val proposals: FileOperationRepository,
    private val service: FileOperationService,
    private val admissionBlocked: () -> Boolean = { false },
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val prepareBinDirectories: (suspend (FileOperationProposal) -> BinDirectoryPreparationResult)? = null,
    private val executeMoves: (suspend (FileOperationProposal, (FileExecutionProgress) -> Unit) -> FileExecutionResult)? = null,
) {
    private val control = Mutex()
    private val mutableState = MutableStateFlow(FileOperationState())
    val state: StateFlow<FileOperationState> = mutableState.asStateFlow()

    fun selectGroups(groups: List<SelectedDeletionGroup>) {
        if (mutableState.value.busy) {
            mutableState.update { it.copy(error = FileOperationFinding(FileOperationIssueCode.BUSY)) }
        } else {
            mutableState.update { it.copy(selectedGroups = groups.distinctBy { group -> group.key }.toList(),
                proposal = null, error = null, directoryPreparation = null, executionProgress = null, executionResult = null) }
        }
    }

    fun refresh() = action(FileOperationIssueCode.PROPOSAL_ERROR) { refreshInAction() }

    fun configureBin(path: String) = action(FileOperationIssueCode.CONFIGURATION_ERROR) {
        checkAdmission()
        repository.configureBinRoot(path.trim().toPath(), clock())
        mutableState.update { it.copy(directoryPreparation = null) }
        refreshInAction()
    }

    fun createDryRun() = action(FileOperationIssueCode.PROPOSAL_ERROR) {
        checkAdmission()
        val selected = mutableState.value.selectedGroups
        val proposal = service.createProposal(selected)
        mutableState.update { it.copy(proposal = proposal, directoryPreparation = null, executionProgress = null, executionResult = null) }
        refreshInAction()
    }

    fun openProposal(id: String) = action(FileOperationIssueCode.PROPOSAL_ERROR) {
        val proposal = proposals.load(id)
        mutableState.update { it.copy(proposal = proposal, directoryPreparation = null, executionProgress = null, executionResult = null) }
    }

    fun prepareDirectories(expectedProposalId: String) = action(FileOperationIssueCode.DIRECTORY_PREPARATION_ERROR) {
        mutableState.update { it.copy(directoryPreparation = null) }
        checkAdmission()
        val proposal = checkNotNull(mutableState.value.proposal) { "Open a passed dry run first." }
        check(proposal.id == expectedProposalId) { "The displayed proposal changed. Review it again." }
        check(proposal.validationStatus == FileOperationValidationStatus.READY) { "The dry run needs attention." }
        val prepare = checkNotNull(prepareBinDirectories) { "Directory preparation is unavailable." }
        val result = repository.scanning {
            checkAdmission()
            check(repository.binRoot() == proposal.binRoot) { "The configured Bin changed. Create a new dry run." }
            prepare(proposal)
        }
        mutableState.update { it.copy(directoryPreparation = result) }
    }

    fun runRealOperation(expectedProposalId: String) = action(FileOperationIssueCode.EXECUTION_ERROR) {
        checkAdmission()
        val proposal = checkNotNull(mutableState.value.proposal) { "Open a passed dry run first." }
        check(proposal.id == expectedProposalId) { "The displayed proposal changed. Review it again." }
        check(proposal.validationStatus == FileOperationValidationStatus.READY) { "The dry run needs attention." }
        check(mutableState.value.executionResult?.operationId != proposal.id) {
            "This proposal already ran. Review the results and create a new dry run."
        }
        val execute = checkNotNull(executeMoves) { "Real runs are unavailable." }
        mutableState.update { it.copy(executionProgress = null, executionResult = null) }
        var lastPublished: Long? = null
        val result = repository.scanning {
            checkAdmission()
            check(repository.binRoot() == proposal.binRoot) { "The configured Bin changed. Create a new dry run." }
            execute(proposal) { progress ->
                val now = clock()
                val previous = lastPublished
                if (previous == null || progress.finished || now - previous >= 200) {
                    mutableState.update { it.copy(executionProgress = progress) }
                    lastPublished = now
                }
            }
        }
        val completed = result.items.count { it.status == FileMoveExecutionStatus.COMPLETED }
        mutableState.update { it.copy(
            executionResult = result,
            executionProgress = FileExecutionProgress(
                total = result.items.size,
                completed = completed,
                failed = result.items.count { item -> item.status == FileMoveExecutionStatus.FAILED },
                uncertain = result.items.count { item -> item.status == FileMoveExecutionStatus.UNCERTAIN },
                finished = true,
                logicalBytesMoved = result.items.filter { item -> item.status == FileMoveExecutionStatus.COMPLETED }
                    .sumOf { item -> item.move.sizeBytes },
            ),
            selectedGroups = if (completed > 0) emptyList() else it.selectedGroups,
        ) }
        refreshInAction()
    }

    private suspend fun refreshInAction() {
        val binRoot = repository.binRoot()
        val roots = repository.registeredBinRoots()
        val history = proposals.recent()
        mutableState.update { it.copy(binRoot = binRoot, registeredBinRoots = roots, history = history) }
    }

    private fun checkAdmission() {
        if (admissionBlocked()) throw FileOperationException(FileOperationFinding(FileOperationIssueCode.BUSY))
    }

    private fun action(errorCode: FileOperationIssueCode, block: suspend () -> Unit) {
        if (!scope.isActive || !control.tryLock()) {
            mutableState.update { it.copy(error = FileOperationFinding(FileOperationIssueCode.BUSY)) }
            return
        }
        mutableState.update { it.copy(busy = true, error = null) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                block()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: FileOperationException) {
                mutableState.update { it.copy(error = failure.finding) }
            } catch (failure: Exception) {
                Diagnostics.logger.e("FileOperations", "${errorCode.name}: ${failure.message}")
                mutableState.update { it.copy(error = FileOperationFinding(errorCode, detail = failure.message)) }
            } finally {
                mutableState.update { it.copy(busy = false,
                    executionProgress = it.executionProgress?.copy(finished = true)) }
                control.unlock()
            }
        }
    }
}
