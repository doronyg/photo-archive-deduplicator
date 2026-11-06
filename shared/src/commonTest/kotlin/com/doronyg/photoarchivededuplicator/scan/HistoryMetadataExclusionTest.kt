package com.doronyg.photoarchivededuplicator.scan

import com.doronyg.photoarchivededuplicator.operations.history.ARCHIVE_MARKER_NAME
import com.doronyg.photoarchivededuplicator.operations.history.HISTORY_DIRECTORY_NAME
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoryMetadataExclusionTest {
    @Test
    fun desktopScannerNeverInventoriesIdentityMarkersOrHistoryDirectories() = concurrencyTest { f ->
        f.write(f.root / ARCHIVE_MARKER_NAME, "{}")
        f.write(f.root / "$ARCHIVE_MARKER_NAME.tmp-pending", "{}")
        f.write(f.root / ARCHIVE_MARKER_NAME.uppercase(), "{}")
        f.write(f.root / "$ARCHIVE_MARKER_NAME.lock" / "temporary", "{}")
        f.write(f.root / HISTORY_DIRECTORY_NAME / "manifest.json", "{}")
        f.write(f.root / "photo.jpg")
        f.scanner().startScan(f.root)
        assertEquals(listOf("photo.jpg"), f.repository.allFiles().map { it.name })
        assertNull(f.repository.directory(f.root / HISTORY_DIRECTORY_NAME))
        f.scanner().startScan(f.root / HISTORY_DIRECTORY_NAME)
        assertNull(f.repository.directory(f.root / HISTORY_DIRECTORY_NAME))
    }

    @Test
    fun mediaAndImportedListingsAlsoExcludeHistoryMetadata() = concurrencyTest { f ->
        val metadataPaths = listOf(f.root / ARCHIVE_MARKER_NAME,
            f.root / HISTORY_DIRECTORY_NAME / "manifest.json")
        val scanner = MediaIndexScanner(MediaSource {
            (metadataPaths + (f.root / "photo.jpg")).map { MediaItem(it, 1, 2) }
        }, f.repository, f.dispatcher)
        scanner.startScan(f.root)
        assertEquals(listOf("photo.jpg"), f.repository.allFiles().map { it.name })
        f.repository.recordListing(f.root, DirectoryListing(
            listOf(DirectoryListing.Entry(f.root / HISTORY_DIRECTORY_NAME, 0, null)),
            metadataPaths.map { DirectoryListing.Entry(it, 1, null) }), 3)
        assertTrue(f.repository.allFiles().isEmpty())
        assertNull(f.repository.directory(f.root / HISTORY_DIRECTORY_NAME))
    }
}
