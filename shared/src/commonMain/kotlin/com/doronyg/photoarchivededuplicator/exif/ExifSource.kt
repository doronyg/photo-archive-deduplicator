package com.doronyg.photoarchivededuplicator.exif

import com.doronyg.photoarchivededuplicator.scan.FileRecord

internal fun interface ExifSource {
    suspend fun read(file: FileRecord): ExifReadResult
}
