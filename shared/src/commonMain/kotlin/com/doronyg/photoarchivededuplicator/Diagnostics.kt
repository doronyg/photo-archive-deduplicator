package com.doronyg.photoarchivededuplicator

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.TimeSource

object LogTags {
    const val FILE_SYSTEM = "FileSystem"
    const val IO = "IO"
    const val DATABASE = "Database"
    const val MEDIA_STORE = "MediaStore"
    const val MEDIA_INDEX = "MediaIndex"
    const val NATIVE_PATH = "NativePath"
    const val PICKER = "Picker"
    const val LIFECYCLE = "Lifecycle"
    const val CONTAINER = "Container"
    const val SCAN_CONTROLLER = "ScanController"
    const val RETENTION = "Retention"
}

enum class LogLevel { D, I, W, E }
data class LogEntry(
    val id: Long, val timestamp: Long, val level: LogLevel,
    val tag: String, val thread: String, val message: String,
)
data class LogSnapshot(
    val entries: List<LogEntry> = emptyList(),
    val dropped: Long = 0, val sinkDropped: Long = 0, val sinkFailures: Long = 0,
    val fileDropped: Long = 0, val fileFailures: Long = 0, val fileError: String? = null,
)

internal expect class LogCounter() {
    fun increment(): Long
    fun get(): Long
}
internal expect fun diagnosticThread(): String
internal expect fun platformLog(entry: LogEntry)
/** Resolves a name only, without filesystem access. Null on Android (Logcat only). */
expect fun diagnosticLogDirectory(): String?
internal expect fun platformLogFile(): DiagnosticFileSink?

interface DiagnosticFileSink {
    fun write(entry: LogEntry)
    fun close()
}

fun formatLog(entry: LogEntry): String =
    "${entry.timestamp} #${entry.id} ${entry.thread} ${entry.level}/${entry.tag}: ${entry.message}"

fun formatLogs(entries: List<LogEntry>): String = entries.joinToString("\n", transform = ::formatLog)

/**
 * Producers only capture cheap metadata and trySend; neither platform sinks nor UI run here.
 * Oldest queued events are dropped on overload, keeping recent operation endings.
 * UI retention is 2,000 entries by default, with 8 KiB messages and 5 Hz publication.
 * IDs represent retained queue order, timestamps represent caller time.
 */
class DiagnosticLogger(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    capacity: Int = 2048,
    private val retained: Int = 2000,
    private val sink: (LogEntry) -> Unit = ::platformLog,
    sinkDispatcher: CoroutineDispatcher? = null,
    private val fileSink: DiagnosticFileSink? = null,
) {
    init {
        require(capacity > 0 && retained > 0)
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineName("Diagnostics"))
    // Neither filesystem scans nor a blocked native console may starve persistent logging.
    private val nativeThread = if (sinkDispatcher == null) IoThread("diagnostic-native") else null
    private val fileThread = if (sinkDispatcher == null) IoThread("diagnostic-file") else null
    private val dropped = LogCounter()
    private val sinkDropped = LogCounter()
    private val sinkFailures = LogCounter()
    private val fileDropped = LogCounter()
    private val fileFailures = LogCounter()
    private val fileError = MutableStateFlow<String?>(null)
    private val queue = Channel<LogEntry>(capacity, BufferOverflow.DROP_OLDEST, onUndeliveredElement = { dropped.increment() })
    private val sinkQueue = Channel<LogEntry>(capacity, BufferOverflow.DROP_OLDEST, onUndeliveredElement = { sinkDropped.increment() })
    private val fileQueue = Channel<LogEntry>(capacity, BufferOverflow.DROP_OLDEST, onUndeliveredElement = { fileDropped.increment() })
    private val clearVersion = LogCounter()
    private val mutableState = MutableStateFlow(LogSnapshot())
    val state = mutableState.asStateFlow()

    private val nativeWorker = scope.launch(sinkDispatcher ?: nativeThread!!.dispatcher) {
        for (entry in sinkQueue) {
            try { sink(entry) } catch (_: Throwable) { sinkFailures.increment() }
        }
    }
    private val fileWorker = scope.launch(sinkDispatcher ?: fileThread!!.dispatcher) {
        try {
            for (entry in fileQueue) {
                var failed = false
                try {
                    fileSink?.write(entry)
                } catch (e: Exception) {
                    failed = true
                    fileFailures.increment()
                    fileError.value = "${e::class.simpleName}: ${e.message}".take(1024)
                }
                // Disk failures must not turn a metadata flood into a tight retry loop.
                if (failed) delay(1000)
            }
        } finally {
            try { fileSink?.close() } catch (e: Exception) {
                fileFailures.increment()
                fileError.value = "${e::class.simpleName}: ${e.message}".take(1024)
            }
        }
    }
    private val collector = scope.launch {
            val ring = ArrayDeque<LogEntry>()
            var id = 0L
            var cleared = clearVersion.get()
            var finished = false
            while (isActive && !finished) {
                delay(200)
                var changed = false
                // Bound each batch even when producers never stop.
                for (index in 0 until capacity) {
                    val received = queue.tryReceive()
                    val entry = received.getOrNull()
                    if (entry == null) {
                        finished = received.isClosed
                        break
                    }
                    changed = true
                    val ordered = entry.copy(id = ++id)
                    if (ring.size == retained) ring.removeFirst()
                    ring.addLast(ordered)
                    if (fileSink != null) {
                        if (fileQueue.trySend(ordered).isFailure) fileDropped.increment()
                    }
                    if (sinkQueue.trySend(ordered).isFailure) sinkDropped.increment()
                }
                if (cleared != clearVersion.get()) {
                    changed = true
                    ring.clear()
                    cleared = clearVersion.get()
                }
                val old = mutableState.value
                if (changed || old.dropped != dropped.get() || old.sinkDropped != sinkDropped.get() ||
                    old.sinkFailures != sinkFailures.get() || old.fileDropped != fileDropped.get() ||
                    old.fileFailures != fileFailures.get()) {
                    mutableState.value = LogSnapshot(ring.toList(), dropped.get(), sinkDropped.get(), sinkFailures.get(),
                        fileDropped.get(), fileFailures.get(), fileError.value)
                }
            }
            fileQueue.close()
            sinkQueue.close()
    }

    fun log(level: LogLevel, tag: String, message: String) {
        val entry = LogEntry(0, Clock.System.now().toEpochMilliseconds(), level,
            tag.take(128), diagnosticThread(), message.take(8192))
        // Drop oldest on overload, preserving the latest tail; producers never wait.
        if (queue.trySend(entry).isFailure) dropped.increment()
    }
    fun d(tag: String, message: String) = log(LogLevel.D, tag, message)
    fun i(tag: String, message: String) = log(LogLevel.I, tag, message)
    fun w(tag: String, message: String) = log(LogLevel.W, tag, message)
    fun e(tag: String, message: String) = log(LogLevel.E, tag, message)
    fun clear() { clearVersion.increment() }
    /** Best effort: no draining or joining on a UI thread. */
    fun close() {
        queue.close()
        sinkQueue.close()
        scope.cancel()
        nativeThread?.close()
        fileThread?.close()
    }
    /** Call off the UI thread. A blocked OS/console call must not hold shutdown forever. */
    suspend fun shutdown(timeoutMillis: Long = 2000) {
        queue.close()
        try {
            withTimeoutOrNull(timeoutMillis) {
                collector.join()
                fileWorker.join()
                nativeWorker.join()
            }
        } finally {
            close()
        }
    }

    suspend fun <T> operation(tag: String, name: String, block: suspend () -> T): T {
        val context = currentCoroutineContext()
        return blockingOperation(tag, "$name coroutine=${context[CoroutineName]?.name ?: "-"} dispatcher=${context[kotlin.coroutines.ContinuationInterceptor]}") {
            block()
        }
    }

    inline fun <T> blockingOperation(tag: String, name: String, block: () -> T): T {
        val started = TimeSource.Monotonic.markNow()
        d(tag, "BEGIN $name")
        try {
            return block().also { d(tag, "END $name duration=${started.elapsedNow()}") }
        } catch (e: CancellationException) {
            w(tag, "CANCEL $name duration=${started.elapsedNow()}")
            throw e
        } catch (e: Throwable) {
            e(tag, "FAIL $name duration=${started.elapsedNow()} ${e::class.simpleName}: ${e.message}")
            throw e
        }
    }
}

/** Available before container startup, including when opening the database fails. */
object Diagnostics {
    val logger = DiagnosticLogger(fileSink = platformLogFile())
}
