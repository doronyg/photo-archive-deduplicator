package com.doronyg.photoarchivededuplicator.operations

import com.doronyg.photoarchivededuplicator.operations.history.ArchiveIdentity
import com.doronyg.photoarchivededuplicator.operations.history.historyId
import com.doronyg.photoarchivededuplicator.operations.history.historyLong
import com.doronyg.photoarchivededuplicator.operations.history.historyObject
import com.doronyg.photoarchivededuplicator.operations.history.historyString
import com.doronyg.photoarchivededuplicator.operations.history.resolvePortablePath
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.Path.Companion.toPath

internal const val BIN_DIRECTORY_MARKER_NAME = ".deduplicator-bin-directory.json"
private const val BIN_DIRECTORY_KIND = "photo-archive-deduplicator-bin-directory"

internal data class BinDirectoryMarker(
    val identity: ArchiveIdentity,
    val binRelativeDirectory: String,
    /** Informational only: restoration must resolve the archive ID and relative path. */
    val originalDirectory: String?,
    /** Null for structural ancestors outside the archive; empty for the archive root. */
    val originalArchiveRelativeDirectory: String?,
) {
    fun matchesMapping(expected: BinDirectoryMarker): Boolean =
        identity == expected.identity && binRelativeDirectory == expected.binRelativeDirectory &&
            originalArchiveRelativeDirectory == expected.originalArchiveRelativeDirectory &&
            (originalArchiveRelativeDirectory != null || originalDirectory == expected.originalDirectory)
}

internal fun encodeBinDirectoryMarker(marker: BinDirectoryMarker): String =
    Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("formatVersion", 1)
        put("kind", BIN_DIRECTORY_KIND)
        put("archiveId", marker.identity.archiveId)
        put("registrationToken", marker.identity.registrationToken)
        put("binRelativeDirectory", marker.binRelativeDirectory)
        put("originalDirectory", marker.originalDirectory)
        put("originalArchiveRelativeDirectory", marker.originalArchiveRelativeDirectory)
    }) + "\n"

internal fun decodeBinDirectoryMarker(text: String): BinDirectoryMarker {
    val obj = historyObject(text)
    require(obj.historyLong("formatVersion") == 1L && obj.historyString("kind") == BIN_DIRECTORY_KIND) {
        "Unsupported Bin directory marker"
    }
    fun relative(key: String, nullable: Boolean = false): String? {
        if (nullable && obj.getValue(key) == JsonNull) return null
        return obj.historyString(key).also { if (it.isNotEmpty()) resolvePortablePath("/".toPath(), it) }
    }
    val original = if (obj.getValue("originalDirectory") == JsonNull) null
        else obj.historyString("originalDirectory").also {
            val path = it.toPath()
            require(path.isAbsolute && path == path.normalized()) { "Invalid original directory in Bin marker" }
        }
    return BinDirectoryMarker(
        ArchiveIdentity(historyId(obj.historyString("archiveId")), historyId(obj.historyString("registrationToken"))),
        requireNotNull(relative("binRelativeDirectory")), original,
        relative("originalArchiveRelativeDirectory", nullable = true),
    )
}
