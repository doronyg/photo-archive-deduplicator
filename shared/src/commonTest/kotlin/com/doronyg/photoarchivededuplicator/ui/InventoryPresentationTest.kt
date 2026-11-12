package com.doronyg.photoarchivededuplicator.ui

import com.doronyg.photoarchivededuplicator.strings.AppStrings
import kotlin.test.Test
import kotlin.test.assertEquals

class InventoryPresentationTest {
    @Test
    fun copyFeedbackPreservesSelectedPathsAndExplainsScope() {
        assertEquals(
            "Copied 2 file paths from /Photos/Family album, including subfolders.",
            AppStrings.inventory.copiedFilePaths(2, "/Photos/Family album"),
        )
        assertEquals(
            "Copied 0 file paths from all roots, including subfolders.",
            AppStrings.inventory.copiedFilePaths(0, null),
        )
        assertEquals(
            "Could not copy file paths from all roots: disk unavailable",
            AppStrings.inventory.copyFilePathsFailed(null, "disk unavailable"),
        )
    }

    @Test
    fun errorsPreserveDetailsAndProvideFallbacks() {
        assertEquals("Could not choose folder: access denied", AppStrings.inventory.chooseFolderFailed("access denied"))
        assertEquals("Could not open inventory: Unknown", AppStrings.inventory.openInventoryFailed(null))
        assertEquals("Failed: disconnected", AppStrings.inventory.scanFailed("disconnected"))
    }

    @Test
    fun browseDetailsRetainStatusDatesAndRetries() {
        assertEquals(
            "done - 4 files - 1.5 KB - scanned 2026-11-05 12:00:00 - 2 retries",
            AppStrings.inventory.directoryDetails("done", 4, "1.5 KB", "2026-11-05 12:00:00", 2),
        )
        assertEquals(
            "pending - 0 files - 0 B - scanned -",
            AppStrings.inventory.directoryDetails("pending", 0, "0 B", "-", 0),
        )
        assertEquals("2 folders (done: 2) - 4 files - 1.5 KB", AppStrings.inventory.summary(2, "done: 2", 4, "1.5 KB"))
    }

    @Test
    fun logLabelsRetainDiagnosticCountersAndExplainClearWithoutImplementationNotes() {
        assertEquals("Local diagnostics - epoch milliseconds - 3 retained/visible", AppStrings.inventory.logSummary(3))
        assertEquals(
            "Dropped: 1 - native sink dropped: 2 - sink failures: 3",
            AppStrings.inventory.logSinkStatus(1, 2, 3),
        )
        assertEquals(
            "Files: 5 x 5 MiB maximum - file queue dropped: 4 - file failures: 5",
            AppStrings.inventory.logFileStatus(4, 5),
        )
        assertEquals("Clear affects this view, not saved log files.", AppStrings.inventory.logPersistenceNotice)
    }

    @Test
    fun sharedFormattersPreserveUnitsElapsedAndUnknownDates() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("1m 5s", formatElapsed(65_000))
        assertEquals("-", formatEpochMillis(null))
        assertEquals("1970-01-01 00:00:00", formatEpochMillis(0))
    }
}
