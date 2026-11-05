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
import com.doronyg.photoarchivededuplicator.strings.AppStrings
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
    if (record == null) return ExifPresentation(AppStrings.duplicates.exifNotExtracted, emptyList())
    val result = record.result
    val metadata = result.metadata
    val status = when (result.status) {
        ExifStatus.EXTRACTED -> AppStrings.duplicates.extracted
        ExifStatus.NO_EXIF -> AppStrings.duplicates.noExif
        ExifStatus.UNSUPPORTED -> AppStrings.duplicates.unsupported
        ExifStatus.ERROR -> AppStrings.duplicates.error
        ExifStatus.STALE -> AppStrings.duplicates.stale
        ExifStatus.LIMIT_EXCEEDED -> AppStrings.duplicates.limitExceeded
    }
    val offset = metadata.offsetOriginal?.takeIf { it.isNotBlank() }
    val summary = buildList {
        add(AppStrings.duplicates.exifStatus(status))
        metadata.capturedAt?.let { add(AppStrings.duplicates.captured(it, offset ?: AppStrings.duplicates.timezoneUnknown)) }
        listOfNotNull(metadata.make, metadata.model).joinToString(" ").takeIf { it.isNotBlank() }?.let(::add)
        if (metadata.width != null || metadata.height != null) {
            add(AppStrings.duplicates.dimensions(metadata.width, metadata.height))
        }
    }.joinToString(" · ")
    return ExifPresentation(summary, buildList {
        add(AppStrings.duplicates.status to status)
        add(AppStrings.duplicates.captureDatetime to (metadata.capturedAt ?: AppStrings.unknown))
        add(AppStrings.duplicates.originalOffset to (offset ?: AppStrings.duplicates.timezoneUnknown))
        add(AppStrings.duplicates.originalSubseconds to (metadata.subsecondOriginal ?: AppStrings.unknown))
        add(AppStrings.duplicates.cameraMake to (metadata.make ?: AppStrings.unknown))
        add(AppStrings.duplicates.cameraModel to (metadata.model ?: AppStrings.unknown))
        add(AppStrings.duplicates.orientation to (metadata.orientation?.toString() ?: AppStrings.unknown))
        add(AppStrings.duplicates.width to (metadata.width?.toString() ?: AppStrings.unknown))
        add(AppStrings.duplicates.height to (metadata.height?.toString() ?: AppStrings.unknown))
        result.error?.let { add(AppStrings.duplicates.error to it) }
        add(AppStrings.duplicates.extracted to AppStrings.utc(formatEpochMillis(record.extractedAt)))
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
            Text(AppStrings.field(label, value), style = MaterialTheme.typography.bodySmall)
        }
    }
}
