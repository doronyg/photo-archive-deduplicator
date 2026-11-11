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
    private val executeMoves: (suspend (FileOperationProposal, (FileExecutionProgress) -> Unit, FileExecutionControl) -> FileExecutionResult)? = null,
) {
    private val control = Mutex()
    private val mutableState = MutableStateFlow(FileOperationState())
    private val activeExecution = MutableStateFlow<FileExecutionControl?>(null)
    val state: StateFlow<FileOperationState> = mutableState.asStateFlow()

    fun cancelOperation() {
        val execution = activeExecution.value ?: return
        execution.requestCancellation()
        mutableState.update {
            if (activeExecution.value === execution && it.busy) it.copy(cancellationRequested = true) else it
        }
    }

    fun selectGroups(groups: List<SelectedDeletionGroup>) {
        if (mutableState.value.busy) {
            mutableState.update { it.copy(error = FileOperationFinding(FileOperationIssueCode.BUSY)) }
        } else {
            val keys = groups.mapTo(mutableSetOf()) { it.key }
            mutableState.update { current ->
                val selected = mergeSelectedDeletionGroups(
                    current.selectedGroups.filter { it.duplicateProvenance != null || it.key in keys }, groups)
                val available = mergeSelectedDeletionGroups(
                    current.availableGroups.filter { it.duplicateProvenance != null || it.key in keys }, groups)
                if (selected == current.selectedGroups) current.copy(availableGroups = available, error = null)
                else current.copy(selectedGroups = selected, availableGroups = available,
                    proposal = null, error = null, directoryPreparation = null, executionProgress = null, executionResult = null)
            }
        }
    }

    fun addDuplicateSelections(selections: List<DuplicateDeletionSelection>) = action(FileOperationIssueCode.PROPOSAL_ERROR) {
        checkAdmission()
        val current = mutableState.value
        val merged = mergeDuplicateDeletionSelections(
            current.availableGroups.mapNotNull { it.duplicateProvenance?.selection }, selections)
        val ids = selections.mapTo(mutableSetOf()) { duplicateDeletionGroupId(it) }
        val additions = service.resolveDuplicateSelections(merged.filter { duplicateDeletionGroupId(it) in ids })
        val selected = mergeSelectedDeletionGroups(current.selectedGroups, additions)
        val available = mergeSelectedDeletionGroups(current.availableGroups, additions)
        mutableState.update {
            if (selected == it.selectedGroups) it.copy(availableGroups = available, error = null)
            else it.copy(selectedGroups = selected, availableGroups = available,
                proposal = null, error = null, directoryPreparation = null,
                executionProgress = null, executionResult = null)
        }
        refreshInAction()
    }

    fun selectAllGroups() = updateGroupSelection { it.availableGroups }

    fun clearGroupSelection() = updateGroupSelection { emptyList() }

    fun setGroupSelected(key: String, selected: Boolean) = updateGroupSelection { current ->
        if (current.availableGroups.none { it.key == key }) null
        else {
            val keys = current.selectedGroups.mapTo(mutableSetOf()) { it.key }
            if (selected) keys.add(key) else keys.remove(key)
            current.availableGroups.filter { it.key in keys }
        }
    }

    private fun updateGroupSelection(select: (FileOperationState) -> List<SelectedDeletionGroup>?) {
        mutableState.update { current ->
            if (current.busy) current.copy(error = FileOperationFinding(FileOperationIssueCode.BUSY))
            else {
                val selection = select(current)
                if (selection == null) current.copy(error = FileOperationFinding(FileOperationIssueCode.STALE_RECOMMENDATION))
                else if (selection == current.selectedGroups) current.copy(error = null)
                else current.copy(selectedGroups = selection, proposal = null, error = null,
                    directoryPreparation = null, executionProgress = null, executionResult = null)
            }
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
        val proposal = checkNotNull(mutableState.value.proposal) { "Open a dry run with valid groups first." }
        check(proposal.id == expectedProposalId) { "The displayed proposal changed. Review it again." }
        check(proposal.canExecute) { "The dry run has no executable groups or has a global error." }
        check(mutableState.value.executionResult?.operationId != proposal.id) {
            "This proposal already ran. Review the results and create a new dry run."
        }
        val execute = checkNotNull(executeMoves) { "Real runs are unavailable." }
        val execution = FileExecutionControl()
        val startedAt = clock()
        activeExecution.value = execution
        mutableState.update { it.copy(executionProgress = FileExecutionProgress(
            total = proposal.moves.size, phase = FileExecutionPhase.PREPARING),
            executionResult = null, cancellationRequested = false) }
        var lastPublished: Long? = null
        val result = try {
            repository.scanning {
                if (execution.isCancellationRequested) {
                    FileExecutionResult(proposal.id, proposal.moves.map {
                        FileMoveExecutionResult(it, FileMoveExecutionStatus.NOT_STARTED)
                    }, cancelled = true)
                } else {
                    checkAdmission()
                    check(repository.binRoot() == proposal.binRoot) { "The configured Bin changed. Create a new dry run." }
                    execute(proposal, { progress ->
                        val now = clock()
                        val previous = lastPublished
                        val shown = mutableState.value.executionProgress
                        if (previous == null || progress.finished || shown?.phase != progress.phase ||
                            shown?.completed != progress.completed || shown?.verified != progress.verified ||
                            shown?.failed != progress.failed || shown?.uncertain != progress.uncertain ||
                            now - previous >= 200) {
                            mutableState.update { it.copy(executionProgress =
                                progress.copy(elapsedMillis = (now - startedAt).coerceAtLeast(0))) }
                            lastPublished = now
                        }
                    }, execution)
                }
            }
        } finally {
            activeExecution.value = null
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
                phase = if (result.cancelled) FileExecutionPhase.CANCELLED else FileExecutionPhase.FINISHED,
                verified = maxOf(completed, it.executionProgress?.verified ?: 0),
                elapsedMillis = (clock() - startedAt).coerceAtLeast(0),
            ),
            selectedGroups = if (completed > 0) emptyList() else it.selectedGroups,
            availableGroups = if (completed > 0) emptyList() else it.availableGroups,
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
                    cancellationRequested = false,
                    executionProgress = it.executionProgress?.copy(finished = true)) }
                control.unlock()
            }
        }
    }
}
