package com.doronyg.photoarchivededuplicator

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Path
import com.doronyg.photoarchivededuplicator.strings.AppStrings

internal data class MediaOpenTarget(val contentUri: String, val mimeType: String)

internal class AndroidPathActions(
    private val lookup: suspend (Path) -> MediaOpenTarget?,
    private val launch: suspend (MediaOpenTarget) -> Unit,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val filesystemLane: IoLane = IoLane("filesystem-test", ioDispatcher),
) : PathActions {
    override suspend fun openFile(path: Path) {
        val target = filesystemLane.run("native MediaStore lookup $path") {
            val found = Diagnostics.logger.operation(LogTags.NATIVE_PATH, "MediaStore lookup $path") { lookup(path) }
                ?: error(AppStrings.platform.mediaMissing(path.toString()))
            check(found.contentUri.startsWith("content://")) { AppStrings.platform.mediaUriRequired }
            found
        }
        withContext(Dispatchers.Main) {
            Diagnostics.logger.operation(LogTags.NATIVE_PATH, "startActivity ${target.contentUri}") { launch(target) }
        }
    }

    override suspend fun openFolder(path: Path): Unit =
        throw UnsupportedOperationException(AppStrings.platform.androidFolderUnsupported(path.toString()))
}

fun androidPathActions(context: Context, loadContainer: suspend () -> AppContainer): PathActions = object : PathActions {
    override suspend fun openFile(path: Path) =
        androidPathActionsForContainer(context, loadContainer()).openFile(path)
    override suspend fun openFolder(path: Path) =
        androidPathActionsForContainer(context, loadContainer()).openFolder(path)
}

private fun androidPathActionsForContainer(context: Context, container: AppContainer): PathActions = AndroidPathActions(
    filesystemLane = container.filesystemLane,
    lookup = { path -> findMediaTarget(context.contentResolver, path) },
    launch = { target ->
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(target.contentUri), target.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    },
)

/** Resolve the existing path at click time, so no URI schema change or file:// exposure is needed. */
private fun findMediaTarget(resolver: ContentResolver, path: Path): MediaOpenTarget? {
    val projection = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.VOLUME_NAME,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
    )
    val cursor = Diagnostics.logger.blockingOperation(LogTags.MEDIA_STORE, "resolver.query path=$path") { resolver.query(
        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), projection,
        "${MediaStore.MediaColumns.DATA} = ? AND ${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)",
        arrayOf(path.toString(), MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()), null,
    ) } ?: error(AppStrings.platform.mediaPermissionsError)
    return Diagnostics.logger.blockingOperation(LogTags.MEDIA_STORE, "cursor consume/close path=$path") { cursor.use {
        if (!it.moveToFirst()) return@use null
        val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
        val volume = it.getString(it.getColumnIndexOrThrow(MediaStore.MediaColumns.VOLUME_NAME))
            ?: error(AppStrings.platform.mediaVolumeMissing)
        val type = it.getInt(it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE))
        val collection = when (type) {
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> MediaStore.Images.Media.getContentUri(volume)
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> MediaStore.Video.Media.getContentUri(volume)
            else -> error(AppStrings.platform.mediaOnly)
        }
        val mime = it.getString(it.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE))
            ?: if (type == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE) "image/*" else "video/*"
        MediaOpenTarget(ContentUris.withAppendedId(collection, id).toString(), mime)
    } }
}
