package com.doronyg.photoarchivededuplicator.operations.history

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okio.Path.Companion.toPath
import kotlin.time.Instant

internal fun historyObject(text: String): JsonObject =
    Json.parseToJsonElement(text) as? JsonObject ?: error("Expected JSON object")

internal fun JsonObject.historyString(key: String): String {
    val value = get(key) as? JsonPrimitive
    require(value != null && value.isString) { "Expected string: $key" }
    return value.content
}

internal fun JsonObject.historyLong(key: String): Long {
    val value = get(key) as? JsonPrimitive
    require(value != null && !value.isString) { "Expected integer: $key" }
    return requireNotNull(value.longOrNull) { "Expected integer: $key" }
}

internal fun JsonObject.historyVersion() = require(historyLong("formatVersion") == 1L) {
    "Unsupported history formatVersion"
}

internal fun historyId(value: String): String {
    require(value.length in 1..128 && value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }) {
        "Invalid history identifier"
    }
    return value
}

internal fun parseIdentity(text: String, bin: Boolean = false): ArchiveIdentity {
    val obj = historyObject(text)
    obj.historyVersion()
    if (bin) historyId(obj.historyString("binId"))
    return ArchiveIdentity(historyId(obj.historyString("archiveId")), historyId(obj.historyString("registrationToken")))
}

private fun timestamp(value: String): String = value.also { Instant.parse(it) }
private fun portable(value: String): String = value.also {
    resolvePortablePath("/".toPath(), it)
    require(it.split('/').none { segment ->
        segment.equals(HISTORY_DIRECTORY_NAME, ignoreCase = true) ||
            segment.equals(ARCHIVE_MARKER_NAME, ignoreCase = true) ||
            segment.startsWith("$ARCHIVE_MARKER_NAME.", ignoreCase = true)
    }) { "Operation path targets reserved application metadata" }
}
private fun JsonObject.nullableLong(key: String): Long? =
    if (getValue(key) == JsonNull) null else historyLong(key)

private data class Event(
    val eventId: String,
    val sequence: Long,
    val itemId: String,
    val attemptId: String,
    val action: String,
    val state: String,
    val occurredAt: String,
    val error: String?,
)

internal fun parseHistoryOperation(
    id: String, archiveId: String, manifest: String, journal: String,
    ensureActive: () -> Unit = {},
): OperationHistoryEntry {
    val obj = historyObject(manifest)
    obj.historyVersion()
    require(historyId(obj.historyString("operationId")) == id) { "Operation identity mismatch" }
    require(obj.historyString("archiveId") == archiveId) { "Archive identity mismatch" }
    val createdAt = timestamp(obj.historyString("createdAt"))
    val items = (obj.getValue("items") as JsonArray).map { element ->
        ensureActive()
        val item = element as JsonObject
        OperationHistoryItem(
            historyId(item.historyString("itemId")),
            portable(item.historyString("originalRelativePath")),
            portable(item.historyString("binRelativePath")),
            item.historyLong("sizeBytes").also { require(it >= 0) { "Negative file size" } },
            item.nullableLong("modifiedAtMillis"), item.nullableLong("createdAtMillis"),
            item.historyString("categoryId"), item.historyString("explanation"),
            if (item.getValue("retainedRelativePath") == JsonNull) null
            else portable(item.historyString("retainedRelativePath")),
        )
    }
    require(items.map { it.itemId }.distinct().size == items.size) { "Duplicate item identity" }
    val originalPaths = items.map { it.originalRelativePath.lowercase() }.toSet()
    require(items.none { it.retainedRelativePath?.lowercase() in originalPaths }) { "Retained replacement is also proposed for movement" }
    validatePathCollisions(items.map { it.binRelativePath })
    validatePathCollisions(items.map { it.originalRelativePath })
    // Include retained paths in case-fold collision detection without rejecting exact reuse.
    validatePathCollisions((items.map { it.originalRelativePath } + items.mapNotNull { it.retainedRelativePath }).distinct())
    val problems = mutableListOf<String>()
    val current = items.associateBy { it.itemId }.toMutableMap()
    val pending = mutableMapOf<String, Event>()
    val attempts = mutableSetOf<String>()
    val seen = mutableMapOf<String, Event>()
    val seenJson = mutableMapOf<String, JsonObject>()
    var sequence = 0L
    var valid = true
    val lines = journal.split('\n')
    if (lines.last().isNotEmpty()) problems += "Torn final journal line ignored"
    try {
        for (line in lines.dropLast(1)) {
            ensureActive()
            val eventJson = historyObject(line)
            val event = eventJson.let {
                it.historyVersion()
                Event(historyId(it.historyString("eventId")), it.historyLong("sequence"),
                    historyId(it.historyString("itemId")), historyId(it.historyString("attemptId")),
                    it.historyString("action"), it.historyString("state"), timestamp(it.historyString("occurredAt")),
                    if (it["error"] == null || it["error"] == JsonNull) null else it.historyString("error"))
            }
            val duplicate = seen[event.eventId]
            if (duplicate != null) {
                require(duplicate == event && seenJson[event.eventId] == eventJson) { "Conflicting duplicate event identity" }
                continue
            }
            require(sequence < Long.MAX_VALUE && event.sequence == sequence + 1) { "Journal sequence gap" }
            seen[event.eventId] = event
            seenJson[event.eventId] = eventJson
            sequence = event.sequence
            var item = requireNotNull(current[event.itemId]) { "Unknown event item" }
            require(event.action == "MOVE" || event.action == "RESTORE") { "Unsupported event action" }
            when (event.state) {
                "INTENT" -> {
                    require(event.itemId !in pending && attempts.add(event.attemptId)) { "Overlapping or reused attempt" }
                    require((event.action == "MOVE") != item.confirmedInBin) { "Invalid move/restore cycle" }
                    pending[event.itemId] = event
                    item = if (event.action == "MOVE") item.copy(moveStatus = OperationHistoryMoveStatus.PENDING)
                    else item.copy(restoreStatus = OperationHistoryRestoreStatus.PENDING)
                }
                "COMPLETED", "FAILED" -> {
                    val intent = pending.remove(event.itemId)
                    require(intent != null && intent.attemptId == event.attemptId && intent.action == event.action) {
                        "Terminal event without matching intent"
                    }
                    val completed = event.state == "COMPLETED"
                    item = if (event.action == "MOVE") item.copy(
                        moveStatus = if (completed) OperationHistoryMoveStatus.COMPLETED else OperationHistoryMoveStatus.FAILED,
                        restoreStatus = if (completed) OperationHistoryRestoreStatus.NOT_STARTED else item.restoreStatus,
                        confirmedInBin = completed,
                        completedMoves = item.completedMoves + if (completed) 1 else 0,
                        movedAt = if (completed) event.occurredAt else item.movedAt,
                        lastError = if (completed) null else event.error ?: "Move failed without recorded detail",
                    ) else item.copy(
                        restoreStatus = if (completed) OperationHistoryRestoreStatus.COMPLETED else OperationHistoryRestoreStatus.FAILED,
                        confirmedInBin = !completed,
                        completedRestores = item.completedRestores + if (completed) 1 else 0,
                        restoredAt = if (completed) event.occurredAt else item.restoredAt,
                        lastError = if (completed) null else event.error ?: "Restoration failed without recorded detail",
                    )
                }
                else -> error("Unsupported event state")
            }
            current[event.itemId] = item
        }
    } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        valid = false
        problems += failure.message ?: "Malformed journal"
    }
    if (pending.isNotEmpty()) problems += "Unconfirmed journal intents"
    if (valid && items.any { current.getValue(it.itemId).moveStatus == OperationHistoryMoveStatus.NOT_STARTED })
        problems += "Planned items have not started"
    return OperationHistoryEntry(id, createdAt, archiveId,
        if (valid) items.map { current.getValue(it.itemId) } else items,
        problems, problems.isNotEmpty(), valid)
}

private fun validatePathCollisions(paths: List<String>) {
    val prefixes = mutableMapOf<String, String>()
    val files = mutableSetOf<String>()
    for (path in paths) {
        require(files.add(path.lowercase())) { "Duplicate or case-colliding path" }
        var prefix = ""
        for (segment in path.split('/')) {
            prefix = if (prefix.isEmpty()) segment else "$prefix/$segment"
            val previous = prefixes.put(prefix.lowercase(), prefix)
            require(previous == null || previous == prefix) { "Case-colliding path segment" }
            require(prefix == path || prefix.lowercase() !in files) { "File/directory path collision" }
        }
    }
    require(paths.map { it.lowercase() }.sorted().zipWithNext().none { (file, next) -> next.startsWith("$file/") }) {
        "File/directory path collision"
    }
}
