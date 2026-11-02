package com.doronyg.photoarchivededuplicator

import app.cash.sqldelight.async.coroutines.awaitAsList
import com.doronyg.photoarchivededuplicator.db.FilesDatabase
import com.doronyg.photoarchivededuplicator.scan.DuplicateFileType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class UiTab(val title: String) {
    SCAN("Scan"), BROWSE("Browse"), DUPLICATES("Duplicates"), EXCLUSIONS("Exclusions"), LOGS("Logs"),
}

data class UiChoices(
    val selectedTab: UiTab = UiTab.SCAN,
    val scanRoot: String = "",
    val browseStack: List<String> = emptyList(),
    val duplicateRoot: String = "",
    val duplicateFileType: DuplicateFileType = DuplicateFileType.ALL,
    val duplicateFilenamePattern: String = "",
    val logFilter: String = "",
    val logMinimum: LogLevel = LogLevel.D,
    val logFollow: Boolean = true,
)

/** App-lifetime writer; edits publish immediately, while only complete snapshots reach SQLite. */
class UiPreferences internal constructor(
    initial: UiChoices,
    scope: CoroutineScope,
    private val save: suspend (UiChoices) -> Unit,
) {
    private val mutableState = MutableStateFlow(initial.copy(browseStack = initial.browseStack.toList()))
    val state: StateFlow<UiChoices> = mutableState.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError.asStateFlow()
    private data class EditGate(val closed: Boolean = false, val editors: Int = 0)
    private val gate = MutableStateFlow(EditGate())
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val worker = scope.launch {
        for (request in requests) {
            val snapshot = mutableState.value
            try {
                save(snapshot)
                mutableError.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = "Could not save UI choices: ${e.message ?: e::class.simpleName}"
                mutableError.value = message
                Diagnostics.logger.e(LogTags.DATABASE, message)
            }
        }
    }

    /** The transform must be side-effect free: concurrent edits may retry it. */
    fun update(transform: (UiChoices) -> UiChoices) {
        gate.update {
            check(!it.closed) { "UI preferences have shut down" }
            it.copy(editors = it.editors + 1)
        }
        try {
            while (true) {
                val previous = mutableState.value
                val next = transform(previous).let { it.copy(browseStack = it.browseStack.toList()) }
                if (next == previous) return
                if (mutableState.compareAndSet(previous, next)) break
            }
            check(requests.trySend(Unit).isSuccess) { "UI preferences writer has stopped" }
        } finally {
            gate.update { it.copy(editors = it.editors - 1) }
        }
    }

    /** Close the producer only after UI disposal, and drain before cancelling the owning scope. */
    internal suspend fun shutdown() = withContext(NonCancellable) {
        // An admitted edit must publish its signal before the channel can be closed.
        gate.update { it.copy(closed = true) }
        gate.first { it.editors == 0 }
        requests.close()
        worker.join()
    }
}

internal suspend fun loadUiChoicesInLane(database: FilesDatabase): UiChoices =
    Diagnostics.logger.operation(LogTags.DATABASE, "load UI choices") {
        decodeUiChoices(database.uiPreferenceQueries.selectAll().awaitAsList().associate { it.key to it.value_ })
    }

internal fun encodeUiChoices(choices: UiChoices): Map<String, String> = buildMap {
    put("selectedTab", choices.selectedTab.name)
    put("scanRoot", choices.scanRoot)
    put("duplicateRoot", choices.duplicateRoot)
    put("duplicateFileType", choices.duplicateFileType.name)
    put("duplicateFilenamePattern", choices.duplicateFilenamePattern)
    put("logFilter", choices.logFilter)
    put("logMinimum", choices.logMinimum.name)
    put("logFollow", choices.logFollow.toString())
    put("browseStack.count", choices.browseStack.size.toString())
    choices.browseStack.forEachIndexed { index, path -> put("browseStack.$index", path) }
}

internal fun decodeUiChoices(
    entries: Map<String, String>,
    warn: (String) -> Unit = { Diagnostics.logger.w(LogTags.DATABASE, it) },
): UiChoices {
    fun <T : Enum<T>> enumValue(key: String, values: List<T>, default: T): T {
        val saved = entries[key] ?: return default
        return values.firstOrNull { it.name == saved } ?: default.also {
            warn("Unknown UI preference $key '$saved'; using ${default.name}")
        }
    }
    val count = entries["browseStack.count"]?.let {
        requireNotNull(it.toIntOrNull()?.takeIf { count -> count >= 0 && count <= entries.size }) {
            "Invalid UI preference browseStack.count '$it'"
        }
    } ?: 0
    val follow = entries["logFollow"]?.let {
        requireNotNull(it.toBooleanStrictOrNull()) { "Invalid UI preference logFollow '$it'" }
    } ?: true
    return UiChoices(
        selectedTab = enumValue("selectedTab", UiTab.entries, UiTab.SCAN),
        scanRoot = entries["scanRoot"] ?: "",
        browseStack = List(count) { index -> requireNotNull(entries["browseStack.$index"]) {
            "Missing UI preference browseStack.$index"
        } },
        duplicateRoot = entries["duplicateRoot"] ?: "",
        duplicateFileType = enumValue("duplicateFileType", DuplicateFileType.entries, DuplicateFileType.ALL),
        duplicateFilenamePattern = entries["duplicateFilenamePattern"] ?: "",
        logFilter = entries["logFilter"] ?: "",
        logMinimum = enumValue("logMinimum", LogLevel.entries, LogLevel.D),
        logFollow = follow,
    )
}
