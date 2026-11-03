package com.doronyg.photoarchivededuplicator.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import com.doronyg.photoarchivededuplicator.exif.ExifRecord
import com.doronyg.photoarchivededuplicator.exif.ExifStatus
import com.doronyg.photoarchivededuplicator.scan.DuplicateCandidateGroup
import okio.Path

internal data class ExifPresentation(val summary: String, val details: List<Pair<String, String>>)

internal fun exifGroupKey(group: DuplicateCandidateGroup): Pair<String, Long> = group.name to group.sizeBytes

/** Identity belongs to a search source, not its displayed sort order. */
internal class ExifSnapshotToken {
    private var valid = true
    fun invalidate() { valid = false }
    fun isCurrent(latest: ExifSnapshotToken): Boolean = valid && this === latest
}

internal fun presentExif(record: ExifRecord?): ExifPresentation {
    if (record == null) return ExifPresentation("EXIF: Not extracted", emptyList())
    val result = record.result
    val metadata = result.metadata
    val status = when (result.status) {
        ExifStatus.EXTRACTED -> "Extracted"
        ExifStatus.NO_EXIF -> "No EXIF"
        ExifStatus.UNSUPPORTED -> "Unsupported"
        ExifStatus.ERROR -> "Error"
        ExifStatus.STALE -> "Stale"
        ExifStatus.LIMIT_EXCEEDED -> "Limit exceeded"
    }
    val offset = metadata.offsetOriginal?.takeIf { it.isNotBlank() }
    val summary = buildList {
        add("EXIF: $status")
        metadata.capturedAt?.let { add("$it (${offset ?: "timezone unknown"})") }
        listOfNotNull(metadata.make, metadata.model).joinToString(" ").takeIf { it.isNotBlank() }?.let(::add)
        if (metadata.width != null || metadata.height != null) {
            add("${metadata.width ?: "?"} × ${metadata.height ?: "?"}")
        }
    }.joinToString(" · ")
    return ExifPresentation(summary, buildList {
        add("Status" to result.status.name)
        add("Original capture datetime" to (metadata.capturedAt ?: "unknown"))
        add("Original offset" to (offset ?: "unknown (timezone unknown)"))
        add("Original subseconds" to (metadata.subsecondOriginal ?: "unknown"))
        add("Camera make" to (metadata.make ?: "unknown"))
        add("Camera model" to (metadata.model ?: "unknown"))
        add("Orientation" to (metadata.orientation?.toString() ?: "unknown"))
        add("Width" to (metadata.width?.toString() ?: "unknown"))
        add("Height" to (metadata.height?.toString() ?: "unknown"))
        result.error?.let { add("Error" to it) }
        add("Extracted" to "${formatEpochMillis(record.extractedAt)} UTC")
        add("Reader" to "${record.readerId} ${record.readerVersion}")
    })
}

@Composable
internal fun ExifMetadataLine(path: Path, record: ExifRecord?) {
    var expanded by remember(path, record) { mutableStateOf(false) }
    val presentation = remember(record) { presentExif(record) }
    Column {
        Text(presentation.summary, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.clickable(enabled = record != null, role = Role.Button) { expanded = !expanded })
        if (expanded) presentation.details.forEach { (label, value) ->
            Text("$label: $value", style = MaterialTheme.typography.bodySmall)
        }
    }
}
