package com.doronyg.photoarchivededuplicator.scan

import okio.Path

/** Reported by [DirectoryScanner] as it works; drives progress reporting. */
sealed interface ScanEvent {
    /** Emitted once per run, after interrupted directories are re-queued. */
    data class Started(val pendingDirectories: Long) : ScanEvent

    data class DirectoryStarted(val path: Path) : ScanEvent

    data class DirectoryCompleted(val path: Path, val subdirectoryCount: Int, val fileCount: Int) : ScanEvent

    /** [willRetry] is false once the directory has been marked FAILED. */
    data class DirectoryFailed(val path: Path, val attempts: Int, val willRetry: Boolean, val message: String?) : ScanEvent
}
