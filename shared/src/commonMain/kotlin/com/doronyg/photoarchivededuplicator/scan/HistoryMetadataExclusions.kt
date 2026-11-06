package com.doronyg.photoarchivededuplicator.scan

import com.doronyg.photoarchivededuplicator.operations.history.ARCHIVE_MARKER_NAME
import com.doronyg.photoarchivededuplicator.operations.history.HISTORY_DIRECTORY_NAME
import okio.Path

internal fun Path.isHistoryMetadata(): Boolean = generateSequence(this) { it.parent }.any {
    it.name.equals(HISTORY_DIRECTORY_NAME, ignoreCase = true) ||
        it.name.equals(ARCHIVE_MARKER_NAME, ignoreCase = true) ||
        it.name.startsWith("$ARCHIVE_MARKER_NAME.", ignoreCase = true)
}
