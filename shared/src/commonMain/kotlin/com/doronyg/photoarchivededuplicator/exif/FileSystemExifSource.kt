package com.doronyg.photoarchivededuplicator.exif

import com.doronyg.photoarchivededuplicator.IoLane
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.BufferedSource
import okio.FileMetadata
import okio.FileSystem
import okio.IOException
import okio.buffer
import kotlin.coroutines.CoroutineContext

internal class FileSystemExifSource(
    private val filesystem: FileSystem,
    private val lane: IoLane,
    private val readMetadata: (BufferedSource, Long, CoroutineContext) -> ExifReadResult,
) : ExifSource {
    constructor(filesystem: FileSystem, lane: IoLane, reader: KimExifReader = KimExifReader()) :
        this(filesystem, lane, reader::read)

    override suspend fun read(file: FileRecord): ExifReadResult = lane.run("read EXIF ${file.path}") {
        val context = currentCoroutineContext()
        context.ensureActive()
        try {
            val before = filesystem.metadataOrNull(file.path)
            context.ensureActive()
            if (before == null || !before.matches(file)) {
                return@run stale("File is missing, is not a regular file, or no longer matches the inventory.")
            }
            val result = filesystem.source(file.path).buffer().use {
                context.ensureActive()
                readMetadata(it, file.sizeBytes, context)
            }
            context.ensureActive()
            val after = filesystem.metadataOrNull(file.path)
            context.ensureActive()
            if (after == null || !after.matches(file) || before.fingerprint() != after.fingerprint()) {
                stale("File disappeared or changed during EXIF extraction.")
            } else {
                result
            }
        } catch (e: IOException) {
            context.ensureActive()
            ExifReadResult(ExifStatus.ERROR, error = (e.message ?: "Unable to read EXIF source.").take(500))
        } catch (e: SecurityException) {
            context.ensureActive()
            ExifReadResult(ExifStatus.ERROR, error = (e.message ?: "EXIF source access denied.").take(500))
        }
    }

    private fun FileMetadata.matches(file: FileRecord): Boolean =
        isRegularFile && symlinkTarget == null && size == file.sizeBytes &&
            (file.modifiedAt == null || lastModifiedAtMillis == file.modifiedAt) &&
            // Compare creation only when both scans expose it; some filesystems do not support birth time.
            (file.createdAt == null || createdAtMillis == null || createdAtMillis == file.createdAt)

    private fun FileMetadata.fingerprint(): List<Any?> =
        listOf(isRegularFile, symlinkTarget, size, lastModifiedAtMillis, createdAtMillis)

    private fun stale(reason: String) = ExifReadResult(ExifStatus.STALE, error = reason)
}
