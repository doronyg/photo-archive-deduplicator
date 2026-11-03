package com.doronyg.photoarchivededuplicator.exif

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KimExifReaderTest {
    @Test
    fun preservesOriginalCaptureComponentsWithoutTimezoneConversion() {
        val data = exifTiff()
        val result = KimExifReader().read(Buffer().write(data), data.size.toLong(), EmptyCoroutineContext)
        assertEquals(ExifStatus.EXTRACTED, result.status)
        assertEquals(ExifMetadata("2015:07:23 15:56:29", "+03:00", "123", "Example", "Camera",
            6, 640, 480), result.metadata)
    }

    @Test
    fun missingOriginalOffsetAndSubsecondsRemainUnknown() {
        val data = exifTiff(includeOffset = false)
        val result = KimExifReader().read(Buffer().write(data), data.size.toLong(), EmptyCoroutineContext)
        assertEquals("2015:07:23 15:56:29", result.metadata.capturedAt)
        assertNull(result.metadata.offsetOriginal)
        assertNull(result.metadata.subsecondOriginal)
    }

    @Test
    fun preservesQuestionableRawCaptureDatesForLaterAssessment() {
        val raw = "0000:00:00 00:00:00"
        val data = exifTiff(capturedAt = raw)
        val result = KimExifReader().read(Buffer().write(data), data.size.toLong(), EmptyCoroutineContext)
        assertEquals(ExifStatus.EXTRACTED, result.status)
        assertEquals(raw, result.metadata.capturedAt)
    }

    @Test
    fun distinguishesNoExifUnsupportedAndMalformedFiles() {
        val reader = KimExifReader()
        val result = reader.read(Buffer().write(jpegWithoutExif), jpegWithoutExif.size.toLong(), EmptyCoroutineContext)
        assertEquals(ExifStatus.NO_EXIF, result.status)
        assertEquals(3, result.metadata.width)
        assertEquals(2, result.metadata.height)
        val unknown = "not an image at all".encodeToByteArray()
        assertEquals(ExifStatus.UNSUPPORTED,
            reader.read(Buffer().write(unknown), unknown.size.toLong(), EmptyCoroutineContext).status)
        val truncated = byteArrayOf(73, 73, 42, 0, 8, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0)
        assertEquals(ExifStatus.ERROR,
            reader.read(Buffer().write(truncated), truncated.size.toLong(), EmptyCoroutineContext).status)
    }

    @Test
    fun readBudgetIsReportedRatherThanReturningPartialSuccess() {
        val data = exifTiff()
        val result = KimExifReader(maxBytes = 20).read(Buffer().write(data), data.size.toLong(), EmptyCoroutineContext)
        assertEquals(ExifStatus.LIMIT_EXCEEDED, result.status)
        assertTrue(result.error.orEmpty().contains("limit", ignoreCase = true))
    }

    @Test
    fun jpegMetadataStopsBeforeImagePayloadAndClosesItsStream() {
        val header = Buffer().write(jpegWithoutExif)
        var closed = false
        val stream = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                check(header.size > 0) { "Image payload must not be read for EXIF." }
                return header.read(sink, byteCount)
            }
            override fun timeout(): Timeout = Timeout.NONE
            override fun close() { closed = true }
        }
        val result = KimExifReader().read(stream.buffer(), 1_000_000_000, EmptyCoroutineContext)
        assertEquals(ExifStatus.NO_EXIF, result.status)
        assertTrue(closed)
    }

    @Test
    fun cancellationIsNeverConvertedIntoAFileError() {
        val job = Job().apply { cancel() }
        val data = exifTiff()
        assertFailsWith<CancellationException> {
            KimExifReader().read(Buffer().write(data), data.size.toLong(), job)
        }
    }
}

internal val jpegWithoutExif = byteArrayOf(
    0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xc0.toByte(), 0, 17,
    8, 0, 2, 0, 3, 3, 1, 0x11, 0, 2, 0x11, 0, 3, 0x11, 0,
    0xff.toByte(), 0xda.toByte(), 0, 12, 3, 1, 0, 2, 0x11, 3, 0x11, 0, 63, 0,
)

/** Project-authored little-endian TIFF fixture, without image bytes or third-party assets. */
internal fun exifTiff(includeOffset: Boolean = true, capturedAt: String = "2015:07:23 15:56:29"): ByteArray {
    val ifd0Tags = listOf(256, 257, 271, 272, 274, 34665)
    val exifTags = if (includeOffset) listOf(36867, 36881, 37521) else listOf(36867)
    val exifOffset = 8 + 2 + ifd0Tags.size * 12 + 4
    var valueOffset = exifOffset + 2 + exifTags.size * 12 + 4
    val values = Buffer()
    fun Buffer.entry(tag: Int, type: Int, count: Int, value: Int) {
        writeShortLe(tag).writeShortLe(type).writeIntLe(count).writeIntLe(value)
    }
    fun Buffer.text(tag: Int, text: String) {
        val bytes = (text + "\u0000").encodeToByteArray()
        if (bytes.size <= 4) {
            var packed = 0
            bytes.forEachIndexed { index, byte -> packed = packed or ((byte.toInt() and 255) shl (8 * index)) }
            entry(tag, 2, bytes.size, packed)
        } else {
            entry(tag, 2, bytes.size, valueOffset)
            values.write(bytes)
            valueOffset += bytes.size
        }
    }
    return Buffer().apply {
        write(byteArrayOf(73, 73, 42, 0)).writeIntLe(8)
        writeShortLe(ifd0Tags.size)
        entry(256, 4, 1, 640)
        entry(257, 4, 1, 480)
        text(271, "Example")
        text(272, "Camera")
        entry(274, 3, 1, 6)
        entry(34665, 4, 1, exifOffset)
        writeIntLe(0)
        writeShortLe(exifTags.size)
        text(36867, capturedAt)
        if (includeOffset) {
            text(36881, "+03:00")
            text(37521, "123")
        }
        writeIntLe(0)
        writeAll(values)
    }.readByteArray()
}
