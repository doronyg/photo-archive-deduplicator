package com.doronyg.photoarchivededuplicator.scan

import com.doronyg.photoarchivededuplicator.LogTags

import com.doronyg.photoarchivededuplicator.Diagnostics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Path
import kotlin.time.TimeSource

private const val PROGRESS_INTERVAL_MILLIS = 1_000L
private const val CHECKPOINT_INTERVAL_MILLIS = 5_000L

/** Counters for the current run (a start or a resume), not the whole inventory. */
data class ScanProgress(
    val pendingDirectories: Long = 0,
    val scannedDirectories: Long = 0,
    val failedDirectories: Long = 0,
    val filesFound: Long = 0,
    val currentDirectory: Path? = null,
    val elapsedMillis: Long = 0,
    val totalElapsedMillis: Long = 0,
    val root: Path? = null,
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
 * [onScanAccepted] synchronously gates competing starts before the scan is published.
 * [beforeScan] drains competing work before session initialization or scanner access.
 * [afterScan] runs once per accepted run after children and the final checkpoint finish,
 * never for restored state. Its root can be null if resume initialization failed.
 * Completion requires a successful checkpoint and no explicit pause request.
 * [afterScan] failures are diagnostic only and cannot invalidate the persisted scan outcome.
 */
class ScanController internal constructor(
    private val scanner: InventoryScanner,
    private val scope: CoroutineScope,
    private val repository: DirectoryRepository,
    private val clock: () -> Long = monotonicClock(),
    private val beforeScan: suspend () -> Unit = {},
    private val afterScan: suspend (root: Path?, completed: Boolean) -> Unit = { _, _ -> },
    private val onScanAccepted: () -> Unit = {},
) {
    private val mutableState = MutableStateFlow<ScanState>(ScanState.Idle)
    val state: StateFlow<ScanState> = mutableState.asStateFlow()
    private val stoppedAt = MutableStateFlow<Long?>(null)
    private val controls = Mutex()
    private var pauseRequested = MutableStateFlow(false)
    private var job: Job? = scope.launch {
        try {
            val saved = repository.currentScanSession()
            if (saved != null) {
                val progress = ScanProgress(totalElapsedMillis = saved.activeMillis, root = saved.root)
                mutableState.compareAndSet(ScanState.Idle,
                    if (saved.finished) ScanState.Completed(progress) else ScanState.Paused(progress))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutableState.compareAndSet(ScanState.Idle,
                ScanState.Failed(ScanProgress(), e.message ?: "Could not load scan session."))
        }
    }

    /** Starts (or rescans) [root]. Returns false if a scan is already running. */
    suspend fun start(root: Path): Boolean = controls.withLock {
        launchScan(root, newScan = true) { onEvent -> scanner.startScan(root, onEvent) }
    }

    /** Continues unfinished work, including work left by a previous app session. */
    suspend fun resume(root: Path? = null): Boolean = controls.withLock {
        launchScan(root, newScan = false) { onEvent -> scanner.resume(onEvent) }
    }

    fun pause() {
        Diagnostics.logger.i(LogTags.SCAN_CONTROLLER, "Pause requested")
        pauseRequested.value = true
        stoppedAt.compareAndSet(null, clock())
        job?.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun launchScan(root: Path?, newScan: Boolean, run: suspend (onEvent: suspend (ScanEvent) -> Unit) -> Unit): Boolean {
        Diagnostics.logger.i(LogTags.SCAN_CONTROLLER, "Launch requested root=$root newScan=$newScan state=${mutableState.value::class.simpleName}")
        if (mutableState.value is ScanState.Running) return false
        onScanAccepted()
        val previous = job
        val runPauseRequested = MutableStateFlow(false)
        pauseRequested = runPauseRequested
        stoppedAt.value = null
        mutableState.value = ScanState.Running(ScanProgress())
        // Dispatch first, but still enter try/finally if Pause wins before dispatch.
        job = scope.launch(start = CoroutineStart.ATOMIC) {
            var progress = ScanProgress()
            var session: ScanSessionRecord? = null
            var startedAt: Long? = null
            fun sample(): ScanProgress {
                val elapsed = startedAt?.let { ((stoppedAt.value ?: clock()) - it).coerceAtLeast(0) } ?: 0
                return progress.copy(elapsedMillis = elapsed,
                    totalElapsedMillis = (session?.activeMillis ?: 0) + elapsed)
            }
            var failure: Exception? = null
            try {
                // Joining and initialization are cancellable too; neither may leave Running stuck.
                Diagnostics.logger.operation(LogTags.SCAN_CONTROLLER, "previous scan join") { previous?.join() }
                beforeScan()
                session = if (newScan) repository.beginScanSession(root!!) else repository.resumableScanSession(root)
                progress = progress.copy(root = session.root, totalElapsedMillis = session.activeMillis)
                startedAt = clock()
                mutableState.value = ScanState.Running(progress)
                coroutineScope {
                    val events = Channel<ScanEvent>(Channel.RENDEZVOUS)
                    val checkpoints = Channel<ScanSessionRecord>(Channel.CONFLATED)
                    val checkpointWriter = launch {
                        for (checkpoint in checkpoints) repository.checkpointScanSession(checkpoint)
                    }
                    launch {
                        try {
                            run { event -> events.send(event) }
                        } finally {
                            events.close()
                        }
                    }
                    // This coroutine alone reduces progress; scanner callbacks only hand off events.
                    var nextTick = startedAt + PROGRESS_INTERVAL_MILLIS
                    var nextCheckpoint = startedAt + CHECKPOINT_INTERVAL_MILLIS
                    try {
                        var scanning = true
                        while (scanning) {
                            val now = clock()
                            if (now >= nextTick) {
                                progress = sample()
                                mutableState.value = ScanState.Running(progress)
                                nextTick += ((now - nextTick) / PROGRESS_INTERVAL_MILLIS + 1) * PROGRESS_INTERVAL_MILLIS
                            }
                            if (now >= nextCheckpoint) {
                                checkpoints.trySend(ScanSessionRecord(session.root, progress.totalElapsedMillis))
                                nextCheckpoint += ((now - nextCheckpoint) / CHECKPOINT_INTERVAL_MILLIS + 1) * CHECKPOINT_INTERVAL_MILLIS
                            }
                            select<Unit> {
                                events.onReceiveCatching { result ->
                                    val event = result.getOrNull()
                                    if (event == null) {
                                        scanning = false
                                    } else {
                                        Diagnostics.logger.d(LogTags.SCAN_CONTROLLER, "Progress $event")
                                        val sampled = sample()
                                        progress = sampled.after(event, sampled.elapsedMillis)
                                        mutableState.value = ScanState.Running(progress)
                                    }
                                }
                                onTimeout((nextTick - clock()).coerceAtLeast(0)) {}
                            }
                        }
                    } finally {
                        events.cancel()
                        checkpoints.close()
                        checkpointWriter.cancelAndJoin()
                    }
                }
            } catch (e: CancellationException) {
                failure = e
            } catch (e: Exception) {
                failure = e
            } finally {
                stoppedAt.compareAndSet(null, clock())
                withContext(NonCancellable) {
                    progress = sample().copy(currentDirectory = null)
                    try {
                        session?.let { repository.checkpointScanSession(ScanSessionRecord(it.root, progress.totalElapsedMillis,
                            finished = failure == null)) }
                    } catch (e: Exception) {
                        failure?.let { e.addSuppressed(it) }
                        failure = e
                    }
                    // This is a real run's finalization, never startup restoration. Keep
                    // Running until the hook returns so a new run cannot overtake it.
                    try {
                        afterScan(session?.root ?: root,
                            session != null && failure == null && !runPauseRequested.value)
                    } catch (e: Exception) {
                        Diagnostics.logger.e(LogTags.SCAN_CONTROLLER, "Scan lifecycle hook failed: ${e.message}")
                    }
                    mutableState.value = when (val error = failure) {
                        null -> ScanState.Completed(progress)
                        is CancellationException -> ScanState.Paused(progress)
                        else -> ScanState.Failed(progress, error.message ?: error::class.simpleName.orEmpty())
                    }
                    Diagnostics.logger.i(LogTags.SCAN_CONTROLLER, "Stopped state=${mutableState.value} activeMillis=${progress.elapsedMillis}")
                }
            }
            (failure as? CancellationException)?.let { throw it }
        }
        return true
    }
}

private fun monotonicClock(): () -> Long {
    val origin = TimeSource.Monotonic.markNow()
    return { origin.elapsedNow().inWholeMilliseconds }
}
