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
) {
    private val control = Mutex()
    private val mutableState = MutableStateFlow(FileOperationState())
    val state: StateFlow<FileOperationState> = mutableState.asStateFlow()

    fun selectGroups(groups: List<SelectedDeletionGroup>) {
        if (mutableState.value.busy) {
            mutableState.update { it.copy(error = FileOperationFinding(FileOperationIssueCode.BUSY)) }
        } else {
            mutableState.update { it.copy(selectedGroups = groups.distinctBy { group -> group.key }.toList(),
                proposal = null, error = null) }
        }
    }

    fun refresh() = action(FileOperationIssueCode.PROPOSAL_ERROR) { refreshInAction() }

    fun configureBin(path: String) = action(FileOperationIssueCode.CONFIGURATION_ERROR) {
        checkAdmission()
        repository.configureBinRoot(path.trim().toPath(), clock())
        refreshInAction()
    }

    fun createDryRun() = action(FileOperationIssueCode.PROPOSAL_ERROR) {
        checkAdmission()
        val selected = mutableState.value.selectedGroups
        val proposal = service.createProposal(selected)
        mutableState.update { it.copy(proposal = proposal) }
        refreshInAction()
    }

    fun openProposal(id: String) = action(FileOperationIssueCode.PROPOSAL_ERROR) {
        val proposal = proposals.load(id)
        mutableState.update { it.copy(proposal = proposal) }
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
                mutableState.update { it.copy(busy = false) }
                control.unlock()
            }
        }
    }
}
