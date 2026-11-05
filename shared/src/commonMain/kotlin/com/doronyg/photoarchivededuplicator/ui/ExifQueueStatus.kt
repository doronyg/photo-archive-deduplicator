package com.doronyg.photoarchivededuplicator.ui

import com.doronyg.photoarchivededuplicator.exif.ExifQueuePhase
import com.doronyg.photoarchivededuplicator.exif.ExifQueueState
import com.doronyg.photoarchivededuplicator.exif.ExifQueuePurpose
import com.doronyg.photoarchivededuplicator.scan.SUSPECTED_IMAGE_MIN_SIZE_BYTES
import com.doronyg.photoarchivededuplicator.strings.AppStrings

internal data class ExifQueueRefreshKey(val revision: Long, val terminalPhase: ExifQueuePhase?)

internal fun exifQueueRefreshKey(state: ExifQueueState) = ExifQueueRefreshKey(
    state.revision,
    state.phase.takeIf {
        it == ExifQueuePhase.COMPLETED || it == ExifQueuePhase.CANCELLED || it == ExifQueuePhase.FAILED
    },
)

internal fun presentExifQueue(state: ExifQueueState): String? {
    val progress = AppStrings.duplicates.queueProgress(state.groupsCompleted, state.groupsTotal, state.filesCompleted, state.filesTotal)
    return when (state.phase) {
        ExifQueuePhase.IDLE -> null
        ExifQueuePhase.SCORING -> if (state.purpose == ExifQueuePurpose.SUSPECTED_DUPLICATES) {
            AppStrings.duplicates.queueFinding
        } else AppStrings.duplicates.queueChecking
        ExifQueuePhase.EXTRACTING -> AppStrings.duplicates.queueExtracting(progress)
        ExifQueuePhase.REASSESSING -> AppStrings.duplicates.queueReassessing(progress)
        ExifQueuePhase.COMPLETED -> if (state.groupsTotal == 0) {
            if (state.purpose == ExifQueuePurpose.SUSPECTED_DUPLICATES) {
                AppStrings.duplicates.queueEmptySuspected(SUSPECTED_IMAGE_MIN_SIZE_BYTES)
            } else AppStrings.duplicates.queueEmpty
        } else {
            AppStrings.duplicates.queueCompleted(progress)
        }
        ExifQueuePhase.CANCELLED -> AppStrings.duplicates.queueCancelled(progress)
        ExifQueuePhase.FAILED -> AppStrings.duplicates.queueFailed(state.error ?: AppStrings.unknown, progress)
    }
}
