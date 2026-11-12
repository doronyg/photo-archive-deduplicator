package com.doronyg.photoarchivededuplicator.ui

import com.doronyg.photoarchivededuplicator.exif.ExifInput
import com.doronyg.photoarchivededuplicator.exif.ExifMetadata
import com.doronyg.photoarchivededuplicator.exif.ExifReadResult
import com.doronyg.photoarchivededuplicator.exif.ExifRecord
import com.doronyg.photoarchivededuplicator.exif.ExifStatus
import com.doronyg.photoarchivededuplicator.scan.DuplicateCandidateGroup
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExifPresentationTest {
    @Test
    fun extractionGroupIdentitySurvivesMemberReordering() {
        val directory = "/archive".toPath()
        val first = FileRecord(directory / "a.jpg", directory, "a.jpg", "jpg", 1, null, 1)
        val second = first.copy(path = "/other/a.jpg".toPath(), directoryPath = "/other".toPath())
        val group = DuplicateCandidateGroup("a.jpg", 1, listOf(first, second))
        assertEquals(exifGroupKey(group), exifGroupKey(group.copy(files = group.files.reversed())))
        assertFalse(exifGroupKey(group) == exifGroupKey(group.copy(sizeBytes = 2)))
    }

    @Test
    fun replacedSearchAndDisposedOrInvalidatedSourcesCannotPublish() {
        val first = ExifSnapshotToken()
        assertTrue(first.isCurrent(first))
        val nextSearch = ExifSnapshotToken()
        assertFalse(first.isCurrent(nextSearch))
        first.invalidate()
        assertFalse(first.isCurrent(first))
        assertTrue(nextSearch.isCurrent(nextSearch))
        nextSearch.invalidate()
        assertFalse(nextSearch.isCurrent(nextSearch))
    }

    private fun record(status: ExifStatus, metadata: ExifMetadata = ExifMetadata(), error: String? = null): ExifRecord {
        val directory = "/archive".toPath()
        val file = FileRecord(directory / "a.jpg", directory, "a.jpg", "jpg", 1, null, 1)
        return ExifRecord(ExifInput.from(file), ExifReadResult(status, metadata, error), 1000)
    }

    @Test
    fun missingAndEveryStoredStatusHaveExplicitLabels() {
        assertEquals("EXIF: Not extracted", presentExif(null).summary)
        val labels = mapOf(
            ExifStatus.EXTRACTED to "Extracted", ExifStatus.NO_EXIF to "No EXIF",
            ExifStatus.UNSUPPORTED to "Unsupported", ExifStatus.ERROR to "Error",
            ExifStatus.STALE to "Outdated", ExifStatus.LIMIT_EXCEEDED to "Size limit exceeded",
        )
        labels.forEach { (status, label) ->
            assertTrue(presentExif(record(status)).summary.startsWith("EXIF: $label"))
        }
    }

    @Test
    fun rawCaptureDateHasExplicitUnknownTimezoneWithoutAssumingUtc() {
        val presentation = presentExif(record(ExifStatus.EXTRACTED,
            ExifMetadata(capturedAt = "2024:07:08 09:10:11", subsecondOriginal = "023")))
        assertTrue("2024:07:08 09:10:11" in presentation.summary)
        assertTrue("Timezone unknown" in presentation.summary)
        assertFalse("UTC" in presentation.summary)
        assertEquals("023", presentation.details.toMap()["Subseconds"])
        assertEquals("Timezone unknown", presentation.details.toMap()["Timezone offset"])
        assertEquals("Unknown", presentation.details.toMap()["Camera make"])
    }

    @Test
    fun preservesOffsetCameraDimensionsWithoutReaderImplementationDetails() {
        val presentation = presentExif(record(ExifStatus.EXTRACTED,
            ExifMetadata("2024:07:08 09:10:11", "+05:30", "1", "Maker", "Model", 6, 300, 200)))
        assertTrue("+05:30" in presentation.summary)
        assertFalse("timezone unknown" in presentation.summary)
        assertTrue("Maker Model" in presentation.summary)
        assertTrue("300 x 200" in presentation.summary)
        assertEquals("6", presentation.details.toMap()["Orientation"])
        assertEquals("1970-01-01 00:00:01 UTC", presentation.details.toMap()["Extracted"])
        assertFalse("Reader" in presentation.details.toMap())
    }

    @Test
    fun errorsRemainVisibleEvenWithoutMetadata() {
        val presentation = presentExif(record(ExifStatus.ERROR, error = "Cannot read\n\"file\""))
        assertEquals("Cannot read\n\"file\"", presentation.details.toMap()["Error"])
        assertTrue("Error" in presentation.summary)
        assertEquals("Unknown", presentation.details.toMap()["Capture date and time"])
    }
}
