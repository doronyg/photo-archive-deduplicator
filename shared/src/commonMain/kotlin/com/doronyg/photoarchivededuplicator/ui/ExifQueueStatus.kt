package com.doronyg.photoarchivededuplicator.ui

import com.doronyg.photoarchivededuplicator.exif.ExifQueuePhase
import com.doronyg.photoarchivededuplicator.exif.ExifQueueState
import com.doronyg.photoarchivededuplicator.exif.ExifQueuePurpose
import com.doronyg.photoarchivededuplicator.scan.SUSPECTED_IMAGE_MIN_SIZE_BYTES

internal data class ExifQueueRefreshKey(val revision: Long, val terminalPhase: ExifQueuePhase?)

internal fun exifQueueRefreshKey(state: ExifQueueState) = ExifQueueRefreshKey(
    state.revision,
    state.phase.takeIf {
        it == ExifQueuePhase.COMPLETED || it == ExifQueuePhase.CANCELLED || it == ExifQueuePhase.FAILED
    },
)

internal fun presentExifQueue(state: ExifQueueState): String? {
    val progress = "${state.groupsCompleted}/${state.groupsTotal} groups · " +
        "${state.filesCompleted}/${state.filesTotal} files visited (includes cached results, not file reads)"
    return when (state.phase) {
        ExifQueuePhase.IDLE -> null
        ExifQueuePhase.SCORING -> if (state.purpose == ExifQueuePurpose.SUSPECTED_DUPLICATES) {
            "EXIF queue: finding same-size images with different filenames…"
        } else "EXIF queue: checking scores and eligibility…"
        ExifQueuePhase.EXTRACTING -> "EXIF queue: $progress"
        ExifQueuePhase.REASSESSING -> "EXIF queue: updating final retention scores… $progress"
        ExifQueuePhase.COMPLETED -> if (state.groupsTotal == 0) {
            if (state.purpose == ExifQueuePurpose.SUSPECTED_DUPLICATES) {
                "EXIF queue: no same-size images above $SUSPECTED_IMAGE_MIN_SIZE_BYTES bytes with different filenames."
            } else "EXIF queue: no eligible ambiguous groups with a complete folder date."
        } else {
            "EXIF queue completed: $progress"
        }
        ExifQueuePhase.CANCELLED -> "EXIF queue cancelled: $progress. Stored EXIF is preserved; processing stopped."
        ExifQueuePhase.FAILED -> "EXIF queue failed: ${state.error ?: "Unknown error"}. $progress"
    }
}
