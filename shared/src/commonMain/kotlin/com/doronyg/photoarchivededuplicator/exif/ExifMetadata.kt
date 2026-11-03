package com.doronyg.photoarchivededuplicator.exif

import com.doronyg.photoarchivededuplicator.scan.FileRecord
import okio.Path

data class ExifInput(
    val path: Path,
    val directoryPath: Path,
    val sizeBytes: Long,
    val modifiedAt: Long?,
    val createdAt: Long?,
) {
    companion object {
        fun from(file: FileRecord): ExifInput =
            ExifInput(file.path, file.directoryPath, file.sizeBytes, file.modifiedAt, file.createdAt)
    }
}

data class ExifMetadata(
    val capturedAt: String? = null,
    val offsetOriginal: String? = null,
    val subsecondOriginal: String? = null,
    val make: String? = null,
    val model: String? = null,
    val orientation: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
)

enum class ExifStatus { EXTRACTED, NO_EXIF, UNSUPPORTED, ERROR, STALE, LIMIT_EXCEEDED }

data class ExifReadResult(
    val status: ExifStatus,
    val metadata: ExifMetadata = ExifMetadata(),
    val error: String? = null,
)

data class ExifRecord(
    val input: ExifInput,
    val result: ExifReadResult,
    val extractedAt: Long,
    val readerId: String = "kim",
    val readerVersion: String = KIM_VERSION,
)
