package com.doronyg.photoarchivededuplicator.operations

import com.doronyg.photoarchivededuplicator.Diagnostics
import com.doronyg.photoarchivededuplicator.UiPreferences
import com.doronyg.photoarchivededuplicator.operations.history.ArchiveBinding
import com.doronyg.photoarchivededuplicator.operations.history.OperationHistorySnapshot
import com.doronyg.photoarchivededuplicator.operations.history.OperationHistoryStore
import com.doronyg.photoarchivededuplicator.scan.DirectoryRepository
import com.doronyg.photoarchivededuplicator.strings.OperationHistoryStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import okio.Path
import okio.Path.Companion.toPath
import kotlin.time.Clock

internal data class OperationHistoryState(
    val binRoot: Path? = null,
    val snapshot: OperationHistorySnapshot? = null,
    val binding: ArchiveBinding? = null,
    val refreshedAt: Long? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

internal class OperationHistoryController(
    private val scope: CoroutineScope,
    private val repository: DirectoryRepository,
    private val preferences: UiPreferences,
    private val store: OperationHistoryStore,
    private val admissionBlocked: () -> Boolean = { false },
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val control = Mutex()
    private val mutableState = MutableStateFlow(OperationHistoryState())
    val state = mutableState.asStateFlow()

    fun refresh() = action {
        val bin = selectedBin()
        loadHistory(bin)
        val root = preferences.state.value.operationHistoryArchiveRoot
        if (root.isNotBlank()) {
            val binding = store.verify(root.toPath(), bin)
            val saved = preferences.state.value
            check(saved.operationHistoryArchiveId == binding.identity.archiveId &&
                saved.operationHistoryRegistrationToken == binding.identity.registrationToken) {
                OperationHistoryStrings.identityChanged
            }
            publishBinding(binding)
        }
    }

    fun connect(archiveRoot: String) = action {
        val bin = selectedBin()
        val binding = store.verify(archiveRoot.trim().toPath(), bin)
        loadHistory(bin)
        publishBinding(binding)
        saveMapping(binding)
    }

    fun register(archiveRoot: String) = action {
        check(!admissionBlocked()) { OperationHistoryStrings.busyRestriction }
        val bin = selectedBin()
        val binding = repository.scanning {
            check(!admissionBlocked()) { OperationHistoryStrings.busyRestriction }
            store.register(archiveRoot.trim().toPath(), bin)
        }
        loadHistory(bin)
        publishBinding(binding)
        saveMapping(binding)
    }

    private suspend fun selectedBin(): Path {
        val bin = checkNotNull(repository.binRoot()) { OperationHistoryStrings.configureBin }
        mutableState.update {
            if (it.binRoot == bin) it.copy(binding = null)
            else OperationHistoryState(binRoot = bin, busy = true)
        }

        return bin
    }

    private fun saveMapping(binding: ArchiveBinding) = preferences.update {
        it.copy(operationHistoryArchiveRoot = binding.archiveRoot.toString(),
            operationHistoryArchiveId = binding.identity.archiveId,
            operationHistoryRegistrationToken = binding.identity.registrationToken)
    }

    private suspend fun publishBinding(binding: ArchiveBinding) {
        check(repository.binRoot() == binding.binRoot) { OperationHistoryStrings.binChanged }
        check(mutableState.value.snapshot?.identity == binding.identity) { OperationHistoryStrings.identityChanged }
        mutableState.update { it.copy(binding = binding) }
    }

    private suspend fun loadHistory(bin: Path) {
        val snapshot = store.read(bin)
        check(repository.binRoot() == bin) { OperationHistoryStrings.binChanged }
        check(snapshot.readable || mutableState.value.snapshot == null) {
            snapshot.problems.joinToString("\n") { it.message }.ifBlank { OperationHistoryStrings.requestFailed }
        }
        mutableState.update { it.copy(snapshot = snapshot, refreshedAt = clock(), binding = null) }
    }

    private fun action(block: suspend () -> Unit) {
        if (!scope.isActive || !control.tryLock()) {
            mutableState.update { it.copy(error = OperationHistoryStrings.alreadyBusy) }
            return
        }
        mutableState.update { it.copy(busy = true, error = null, binding = null) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                block()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                Diagnostics.logger.e("OperationHistory", "History request failed: ${failure.message}")
                mutableState.update { it.copy(error = failure.message ?: OperationHistoryStrings.requestFailed, binding = null) }
            } finally {
                mutableState.update { it.copy(busy = false) }
                control.unlock()
            }
        }
    }
}
