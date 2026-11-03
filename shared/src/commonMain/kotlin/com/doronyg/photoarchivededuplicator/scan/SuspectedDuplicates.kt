package com.doronyg.photoarchivededuplicator.scan

const val SUSPECTED_IMAGE_MIN_SIZE_BYTES: Long = 100_000L

data class ImageSizeGroup(val sizeBytes: Long, val files: List<FileRecord>)

data class SuspectedDuplicateGroup(
    val sizeBytes: Long,
    val capturedAt: String,
    val offsetOriginal: String?,
    val files: List<FileRecord>,
)
