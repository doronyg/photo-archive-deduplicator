package com.doronyg.photoarchivededuplicator.exif

import com.doronyg.photoarchivededuplicator.Diagnostics
import com.doronyg.photoarchivededuplicator.LogTags
import com.doronyg.photoarchivededuplicator.retention.RetentionScoreService
import com.doronyg.photoarchivededuplicator.scan.DirectoryRepository
import com.doronyg.photoarchivededuplicator.scan.DuplicateCandidateGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Path

enum class ExifQueuePhase { IDLE, SCORING, EXTRACTING, REASSESSING, COMPLETED, CANCELLED, FAILED }

data class ExifQueueState(
    val phase: ExifQueuePhase = ExifQueuePhase.IDLE,
    val groupsTotal: Int = 0,
    val groupsCompleted: Int = 0,
    val filesTotal: Int = 0,
    val filesCompleted: Int = 0,
    val error: String? = null,
    val revision: Long = 0,
) {
    val isRunning: Boolean get() = phase == ExifQueuePhase.SCORING ||
        phase == ExifQueuePhase.EXTRACTING || phase == ExifQueuePhase.REASSESSING
}

/** App-owned, read-only source work. Scan lifecycle callers must cancel before starting a scan. */
class ExifQueueController internal constructor(
    private val repository: DirectoryRepository,
    private val retentionScores: RetentionScoreService,
    private val exif: ExifExtractionService,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val control = Mutex()
    private var activeJob: Job? = null
    private val scanActive = MutableStateFlow(false)
    private var closed = false
    private val mutableState = MutableStateFlow(ExifQueueState())
    val state: StateFlow<ExifQueueState> = mutableState.asStateFlow()

    fun start(root: Path): Boolean = startQueue { repository.duplicateCandidates(root) }

    fun start(groups: List<DuplicateCandidateGroup>): Boolean = startQueue {
        groups.map { it.copy(files = it.files.toList()) }
    }

    suspend fun cancel() {
        val job = control.withLock { activeJob }
        job?.cancelAndJoin()
    }

    fun scanAccepted() {
        scanActive.value = true
    }

    suspend fun scanStarting() {
        scanActive.value = true
        cancel()
    }

    fun scanStopped() {
        scanActive.value = false
    }

    suspend fun scanCompleted(root: Path): Boolean = control.withLock {
        scanActive.value = false
        startQueueLocked { repository.duplicateCandidates(root) }
    }

    suspend fun shutdown() {
        val job = control.withLock {
            closed = true
            activeJob
        }
        job?.cancelAndJoin()
    }

    private fun startQueue(load: suspend () -> List<DuplicateCandidateGroup>): Boolean {
        if (!control.tryLock()) return false
        try {
            return startQueueLocked(load)
        } finally {
            control.unlock()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun startQueueLocked(load: suspend () -> List<DuplicateCandidateGroup>): Boolean {
        if (activeJob != null || scanActive.value || closed || scope.coroutineContext[Job]?.isActive == false) return false
        mutableState.value = ExifQueueState(phase = ExifQueuePhase.SCORING, revision = mutableState.value.revision)
        // ATOMIC guarantees cleanup even if the owning scope cancels before the dispatcher runs.
        activeJob = scope.launch(dispatcher, start = CoroutineStart.ATOMIC) { runQueue(load) }
        return true
    }

    private suspend fun publish(transform: (ExifQueueState) -> ExifQueueState) {
        control.withLock {
            currentCoroutineContext().ensureActive()
            mutableState.value = transform(mutableState.value)
        }
    }

    private suspend fun runQueue(load: suspend () -> List<DuplicateCandidateGroup>) {
        var terminal = ExifQueuePhase.CANCELLED
        var failure: String? = null
        try {
            currentCoroutineContext().ensureActive()
            val snapshot = load()
            val initial = retentionScores.assess(snapshot)
            // Assessment is the authority for the configured scorer identity, including custom weights.
            val identity = initial.files.values.firstNotNullOfOrNull { it.score?.identity }
            val queue = if (identity == null) emptyList() else snapshot.filter {
                isExifQueueEligible(it, initial.files, currentIdentity = identity)
            }
            publish { it.copy(groupsTotal = queue.size, filesTotal = queue.sumOf { group -> group.files.size }) }
            if (queue.isNotEmpty()) {
                publish { it.copy(phase = ExifQueuePhase.EXTRACTING) }
                retentionScores.withoutAssessments {
                    var completedFiles = 0
                    for ((index, group) in queue.withIndex()) {
                        currentCoroutineContext().ensureActive()
                        exif.extract(group) { completed, _ ->
                            publish { it.copy(filesCompleted = completedFiles + completed) }
                        }
                        completedFiles += group.files.size
                        publish { it.copy(groupsCompleted = index + 1) }
                    }
                    publish { it.copy(phase = ExifQueuePhase.REASSESSING) }
                }
                retentionScores.assess(queue)
            }
            currentCoroutineContext().ensureActive()
            terminal = ExifQueuePhase.COMPLETED
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            terminal = ExifQueuePhase.FAILED
            failure = error.message ?: error::class.simpleName ?: "EXIF queue failed"
            Diagnostics.logger.e(LogTags.EXIF, "Queue failed: $failure")
        } finally {
            withContext(NonCancellable) {
                control.withLock {
                    mutableState.value = mutableState.value.copy(
                        phase = terminal,
                        error = failure,
                        revision = mutableState.value.revision + 1,
                    )
                    activeJob = null
                }
            }
        }
    }
}
