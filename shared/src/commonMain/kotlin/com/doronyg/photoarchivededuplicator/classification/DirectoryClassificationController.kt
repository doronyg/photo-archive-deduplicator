package com.doronyg.photoarchivededuplicator.classification

import com.doronyg.photoarchivededuplicator.Diagnostics
import com.doronyg.photoarchivededuplicator.LogTags
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import com.doronyg.photoarchivededuplicator.scan.inventoryPath
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
import kotlin.time.Clock

enum class DirectoryClassificationPhase { IDLE, CHECKING, RUNNING, COMPLETED, CANCELLED, FAILED }

data class DirectoryClassificationFailure(val directory: Path, val message: String)

data class DirectoryClassificationJobState(
    val phase: DirectoryClassificationPhase = DirectoryClassificationPhase.IDLE,
    val total: Int = 0,
    val completed: Int = 0,
    val skipped: Int = 0,
    val failures: List<DirectoryClassificationFailure> = emptyList(),
    val revision: Long = 0,
    val context: DirectoryClassificationContext? = null,
    val error: String? = null,
) {
    val failed: Int get() = failures.size
    val remaining: Int get() = total - completed - skipped - failed
    val isRunning: Boolean get() = phase == DirectoryClassificationPhase.CHECKING || phase == DirectoryClassificationPhase.RUNNING
}

internal fun directoryClassificationPaths(groups: List<List<FileRecord>>): List<Path> =
    groups.asSequence()
        .filter { it.size >= 2 && it.map { file -> file.path }.distinct().size >= 2 && it.map { file -> file.sizeBytes }.distinct().size == 1 }
        .flatMap { it.asSequence() }.map { it.directoryPath.inventoryPath() }.distinct().sortedBy { it.toString() }.toList()

/** User-triggered HTTP only; classification and persistence never acquire the filesystem lane. */
class DirectoryClassificationController(
    private val scope: CoroutineScope,
    private val classifier: DirectoryClassifier,
    private val load: suspend (List<Path>, DirectoryClassificationContext) -> List<DirectoryClassificationRecord>,
    private val save: suspend (DirectoryClassificationRecord) -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val control = Mutex()
    private var activeJob: Job? = null
    private var closed = false
    private val mutableState = MutableStateFlow(DirectoryClassificationJobState())
    val state: StateFlow<DirectoryClassificationJobState> = mutableState.asStateFlow()

    fun start(
        groups: List<List<FileRecord>>,
        context: DirectoryClassificationContext,
        forceRefresh: Boolean = false,
    ): Boolean = startPaths(directoryClassificationPaths(groups), context, forceRefresh)

    fun retryFailed(groups: List<List<FileRecord>>, context: DirectoryClassificationContext): Boolean {
        val previous = state.value
        if (previous.context != context) return false
        val failed = previous.failures.map { it.directory }.toSet()
        return startPaths(directoryClassificationPaths(groups).filter { it in failed }, context, forceRefresh = true)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun startPaths(paths: List<Path>, context: DirectoryClassificationContext, forceRefresh: Boolean): Boolean {
        if (paths.isEmpty() || !control.tryLock()) return false
        try {
            if (closed || activeJob != null || scope.coroutineContext[Job]?.isActive == false) return false
            val snapshot = context
            mutableState.value = DirectoryClassificationJobState(
                phase = DirectoryClassificationPhase.CHECKING, total = paths.size,
                revision = state.value.revision, context = snapshot)
            activeJob = scope.launch(dispatcher, start = CoroutineStart.ATOMIC) { run(paths, snapshot, forceRefresh) }
            return true
        } finally {
            control.unlock()
        }
    }

    suspend fun cancel() {
        control.withLock { activeJob }?.cancelAndJoin()
    }

    suspend fun shutdown() {
        val job = control.withLock { closed = true; activeJob }
        job?.cancelAndJoin()
    }

    private suspend fun publish(transform: (DirectoryClassificationJobState) -> DirectoryClassificationJobState) =
        control.withLock { mutableState.value = transform(mutableState.value) }

    private suspend fun run(paths: List<Path>, context: DirectoryClassificationContext, forceRefresh: Boolean) {
        var terminal = DirectoryClassificationPhase.CANCELLED
        var current: Path? = null
        try {
            currentCoroutineContext().ensureActive()
            Diagnostics.logger.i(LogTags.OLLAMA,
                "Classification job starting directories=${paths.size} refresh=$forceRefresh model=${ollamaLogPreview(context.model)}")
            val requested = paths.toSet()
            val cached = if (forceRefresh) emptySet() else load(paths, context).filter {
                it.context == context && it.directoryPath in requested
            }.map { it.directoryPath }.toSet()
            Diagnostics.logger.i(LogTags.OLLAMA,
                "Classification cache checked: skipped=${cached.size} requests=${paths.size - cached.size}")
            publish { it.copy(phase = DirectoryClassificationPhase.RUNNING, skipped = cached.size) }
            for (path in paths) {
                currentCoroutineContext().ensureActive()
                if (path in cached) continue
                current = path
                try {
                    val classification = classifier.classify(path, context)
                    currentCoroutineContext().ensureActive()
                    // Commit the accepted result and its revision together, including during cancellation.
                    withContext(NonCancellable) {
                        save(DirectoryClassificationRecord(path, path.toString(), classification, context, clock()))
                        publish { it.copy(completed = it.completed + 1, revision = it.revision + 1) }
                        Diagnostics.logger.i(LogTags.OLLAMA,
                            "Classification saved directory=${ollamaLogPreview(path.toString())} classification=$classification completed=${state.value.completed}/${paths.size}")
                    }
                } catch (e: DirectoryClassificationException) {
                    val message = e.message ?: "Directory classification request failed."
                    Diagnostics.logger.w(LogTags.OLLAMA,
                        "AI directory classification failed directory=${ollamaLogPreview(path.toString())}: $message")
                    publish { it.copy(failures = it.failures + DirectoryClassificationFailure(path, message)) }
                }
                current = null
            }
            terminal = DirectoryClassificationPhase.COMPLETED
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            terminal = DirectoryClassificationPhase.FAILED
            val message = "Directory classification job failed: ${e.message ?: e::class.simpleName}"
            Diagnostics.logger.e(LogTags.OLLAMA, message)
            publish { previous ->
                previous.copy(error = message, failures = current?.let {
                    previous.failures + DirectoryClassificationFailure(it, message)
                } ?: previous.failures)
            }
        } finally {
            withContext(NonCancellable) {
                control.withLock {
                    mutableState.value = mutableState.value.copy(phase = terminal)
                    activeJob = null
                    val result = mutableState.value
                    Diagnostics.logger.i(LogTags.OLLAMA,
                        "Classification job stopped phase=$terminal completed=${result.completed} skipped=${result.skipped} failed=${result.failed} remaining=${result.remaining}")
                }
            }
        }
    }
}
