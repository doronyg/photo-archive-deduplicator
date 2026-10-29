package com.doronyg.photoarchivededuplicator.scan

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okio.Path
import kotlin.time.Clock

/** Counters for the current run (a start or a resume), not the whole inventory. */
data class ScanProgress(
    val pendingDirectories: Long = 0,
    val scannedDirectories: Long = 0,
    val failedDirectories: Long = 0,
    val filesFound: Long = 0,
    val currentDirectory: Path? = null,
    val elapsedMillis: Long = 0,
) {
    internal fun after(event: ScanEvent, elapsedMillis: Long): ScanProgress = when (event) {
        is ScanEvent.Started -> copy(pendingDirectories = event.pendingDirectories)
        is ScanEvent.DirectoryStarted -> copy(currentDirectory = event.path)
        is ScanEvent.DirectoryCompleted -> copy(
            pendingDirectories = pendingDirectories - 1 + event.subdirectoryCount,
            scannedDirectories = scannedDirectories + 1,
            filesFound = filesFound + event.fileCount,
        )
        is ScanEvent.DirectoryFailed -> if (event.willRetry) this else copy(
            pendingDirectories = pendingDirectories - 1,
            failedDirectories = failedDirectories + 1,
        )
    }.copy(elapsedMillis = elapsedMillis)
}

sealed interface ScanState {
    data object Idle : ScanState
    data class Running(val progress: ScanProgress) : ScanState
    data class Paused(val progress: ScanProgress) : ScanState
    data class Completed(val progress: ScanProgress) : ScanState
    data class Failed(val progress: ScanProgress, val message: String) : ScanState
}

/**
 * Runs at most one scan at a time in [scope] and publishes its [state].
 * Pausing cancels the scan; all progress is already persisted, so [resume] continues it.
 */
class ScanController(
    private val scanner: InventoryScanner,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val mutableState = MutableStateFlow<ScanState>(ScanState.Idle)
    val state: StateFlow<ScanState> = mutableState.asStateFlow()
    private var job: Job? = null

    /** Starts (or rescans) [root]. Returns false if a scan is already running. */
    fun start(root: Path): Boolean = launchScan { onEvent -> scanner.startScan(root, onEvent) }

    /** Continues unfinished work, including work left by a previous app session. */
    fun resume(): Boolean = launchScan { onEvent -> scanner.resume(onEvent) }

    fun pause() {
        job?.cancel()
    }

    private fun launchScan(run: suspend (onEvent: suspend (ScanEvent) -> Unit) -> Unit): Boolean {
        if (mutableState.value is ScanState.Running) return false
        val previous = job
        mutableState.value = ScanState.Running(ScanProgress())
        job = scope.launch {
            // A paused scan may still be finishing its last directory
            previous?.join()
            val startedAt = clock()
            var progress = ScanProgress()
            try {
                run { event ->
                    progress = progress.after(event, clock() - startedAt)
                    mutableState.value = ScanState.Running(progress)
                }
                mutableState.value = ScanState.Completed(progress.copy(currentDirectory = null))
            } catch (e: CancellationException) {
                mutableState.value = ScanState.Paused(progress.copy(currentDirectory = null))
                throw e
            } catch (e: Exception) {
                mutableState.value = ScanState.Failed(progress, e.message ?: e::class.simpleName.orEmpty())
            }
        }
        return true
    }
}
