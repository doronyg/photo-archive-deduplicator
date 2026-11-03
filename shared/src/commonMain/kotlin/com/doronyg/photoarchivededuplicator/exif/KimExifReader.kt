package com.doronyg.photoarchivededuplicator.exif

import de.stefan_oltmann.kim.Kim
import de.stefan_oltmann.kim.common.ImageReadException
import de.stefan_oltmann.kim.format.tiff.constant.ExifTag
import de.stefan_oltmann.kim.format.tiff.constant.TiffTag
import de.stefan_oltmann.kim.input.ByteReader
import kotlinx.coroutines.ensureActive
import okio.Buffer
import okio.BufferedSource
import okio.IOException
import kotlin.coroutines.CoroutineContext

internal class KimExifReader(private val maxBytes: Long = 16L * 1024 * 1024) {
    init {
        require(maxBytes > 0) { "EXIF read limit must be positive." }
    }

    fun read(source: BufferedSource, contentLength: Long, context: CoroutineContext): ExifReadResult {
        try {
            val metadata = Kim.readMetadata(LimitedByteReader(source, contentLength, maxBytes, context))
                ?: return ExifReadResult(ExifStatus.UNSUPPORTED)
            context.ensureActive()
            // Read only whitelisted raw tags. Summary conversion invents a zone for local EXIF dates.
            val fields = ExifMetadata(
                capturedAt = metadata.findStringValue(ExifTag.EXIF_TAG_DATE_TIME_ORIGINAL),
                offsetOriginal = metadata.findStringValue(ExifTag.EXIF_TAG_OFFSET_TIME_ORIGINAL),
                subsecondOriginal = metadata.findStringValue(ExifTag.EXIF_TAG_SUB_SEC_TIME_ORIGINAL),
                make = metadata.findStringValue(TiffTag.TIFF_TAG_MAKE),
                model = metadata.findStringValue(TiffTag.TIFF_TAG_MODEL),
                orientation = metadata.findShortValue(TiffTag.TIFF_TAG_ORIENTATION)?.toInt(),
                width = metadata.imageSize?.width,
                height = metadata.imageSize?.height,
            )
            return ExifReadResult(
                if (metadata.exif == null) ExifStatus.NO_EXIF else ExifStatus.EXTRACTED,
                fields,
            )
        } catch (e: ImageReadException) {
            val limit = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<ExifReadLimitException>().firstOrNull()
            return if (limit != null) ExifReadResult(ExifStatus.LIMIT_EXCEEDED, error = limit.message)
            else ExifReadResult(ExifStatus.ERROR, error = (e.cause?.message ?: e.message ?: "Unreadable metadata").take(500))
        }
    }
}

private class ExifReadLimitException(maxBytes: Long) :
    IOException("EXIF extraction exceeded the $maxBytes byte read limit; no partial metadata was saved.")

/** KIM may retain bytes for random access; cap total reads before allocating requested arrays. */
private class LimitedByteReader(
    private val source: BufferedSource,
    override val contentLength: Long,
    private val maxBytes: Long,
    private val context: CoroutineContext,
) : ByteReader {
    private var consumed = 0L

    override fun readByte(): Byte? {
        context.ensureActive()
        if (consumed >= maxBytes) throw ExifReadLimitException(maxBytes)
        if (source.exhausted()) return null
        consumed++
        return source.readByte()
    }

    override fun readBytes(count: Int): ByteArray {
        context.ensureActive()
        require(count >= 0) { "Negative metadata read length." }
        if (count.toLong() > maxBytes - consumed) throw ExifReadLimitException(maxBytes)
        val result = Buffer()
        while (result.size < count) {
            context.ensureActive()
            val read = source.read(result, minOf(count - result.size, 8192L))
            if (read == -1L) break
            consumed += read
        }
        return result.readByteArray()
    }

    override fun close() = source.close()
}
