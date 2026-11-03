package com.doronyg.photoarchivededuplicator.exif

import android.content.ContentResolver
import android.content.ContentUris
import android.provider.MediaStore
import android.net.Uri
import com.doronyg.photoarchivededuplicator.IoLane
import com.doronyg.photoarchivededuplicator.scan.FileRecord
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.BufferedSource
import okio.IOException
import okio.Path
import okio.Source
import okio.buffer
import okio.source
import kotlin.coroutines.CoroutineContext

internal data class MediaExifTarget(
    val contentUri: String,
    val sizeBytes: Long?,
    val modifiedAt: Long?,
)

internal class AndroidExifSource(
    private val lookup: (Path) -> MediaExifTarget?,
    private val open: (String) -> Source?,
    private val lane: IoLane,
    private val readMetadata: (BufferedSource, Long, CoroutineContext) -> ExifReadResult = KimExifReader()::read,
) : ExifSource {
    constructor(resolver: ContentResolver, lane: IoLane, reader: KimExifReader = KimExifReader()) :
        this(
            lookup = { findExifTarget(resolver, it) },
            open = { resolver.openInputStream(Uri.parse(it))?.source() },
            lane = lane,
            readMetadata = reader::read,
        )

    override suspend fun read(file: FileRecord): ExifReadResult = lane.run("read MediaStore EXIF ${file.path}") {
        val context = currentCoroutineContext()
        context.ensureActive()
        try {
            val before = lookup(file.path)
            context.ensureActive()
            if (before == null || !before.matches(file)) {
                return@run stale("MediaStore item is missing or no longer matches the inventory.")
            }
            if (!before.contentUri.startsWith("content://")) {
                return@run ExifReadResult(ExifStatus.ERROR, error = "A MediaStore content URI is required.")
            }
            val stream = open(before.contentUri)
                ?: return@run ExifReadResult(ExifStatus.ERROR, error = "MediaStore returned no input stream.")
            val result = stream.buffer().use {
                context.ensureActive()
                readMetadata(it, file.sizeBytes, context)
            }
            context.ensureActive()
            val after = lookup(file.path)
            context.ensureActive()
            if (after == null || after != before || !after.matches(file)) {
                stale("MediaStore item disappeared or changed during EXIF extraction.")
            } else {
                result
            }
        } catch (e: IOException) {
            context.ensureActive()
            ExifReadResult(ExifStatus.ERROR, error = (e.message ?: "Unable to read MediaStore item.").take(500))
        } catch (e: SecurityException) {
            context.ensureActive()
            ExifReadResult(ExifStatus.ERROR, error = (e.message ?: "MediaStore access denied.").take(500))
        }
    }

    private fun MediaExifTarget.matches(file: FileRecord): Boolean =
        sizeBytes == file.sizeBytes && (file.modifiedAt == null || modifiedAt == file.modifiedAt)

    private fun stale(reason: String) = ExifReadResult(ExifStatus.STALE, error = reason)
}

private fun findExifTarget(resolver: ContentResolver, path: Path): MediaExifTarget? {
    val projection = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.VOLUME_NAME,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
    )
    val cursor = resolver.query(
        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
        projection,
        "${MediaStore.MediaColumns.DATA} = ? AND ${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)",
        arrayOf(path.toString(), MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()),
        null,
    ) ?: throw IOException("MediaStore returned no cursor; check media permissions.")
    return cursor.use {
        if (!it.moveToFirst()) return@use null
        val volume = it.getString(it.getColumnIndexOrThrow(MediaStore.MediaColumns.VOLUME_NAME))
            ?: throw IOException("MediaStore did not supply the media volume.")
        val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
        val type = it.getInt(it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE))
        val collection = when (type) {
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> MediaStore.Images.Media.getContentUri(volume)
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> MediaStore.Video.Media.getContentUri(volume)
            else -> return@use null
        }
        val sizeColumn = it.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
        val modifiedColumn = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
        MediaExifTarget(
            contentUri = ContentUris.withAppendedId(collection, id).toString(),
            sizeBytes = if (it.isNull(sizeColumn)) null else it.getLong(sizeColumn),
            modifiedAt = if (it.isNull(modifiedColumn)) null else it.getLong(modifiedColumn) * 1000L,
        )
    }
}
