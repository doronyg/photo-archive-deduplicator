package com.doronyg.photoarchivededuplicator

import android.content.ContentResolver
import android.content.Context
import android.provider.MediaStore
import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.doronyg.photoarchivededuplicator.db.FilesDatabase
import com.doronyg.photoarchivededuplicator.exif.AndroidExifSource
import com.doronyg.photoarchivededuplicator.scan.MediaIndexScanner
import com.doronyg.photoarchivededuplicator.scan.MediaItem
import com.doronyg.photoarchivededuplicator.scan.MediaSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.Path.Companion.toPath
import okio.IOException

private const val DATABASE_FILE_NAME = "inventory.db"
private const val MILLIS_PER_SECOND = 1000L

/** Parent of every shared-storage volume (internal storage and SD cards). */
val ANDROID_STORAGE_ROOT = "/storage".toPath()

/** Builds the container with the inventory in the app's private database folder. Call once per process. */
suspend fun createAndroidContainer(context: Context): AppContainer = createAppContainer(
    openDriver = {
        Diagnostics.logger.blockingOperation(LogTags.DATABASE, "AndroidSqliteDriver $DATABASE_FILE_NAME") {
            AndroidSqliteDriver(FilesDatabase.Schema.synchronous(), context, DATABASE_FILE_NAME)
        }
    },
    createScanner = { repository, filesystem ->
        MediaIndexScanner(MediaStoreSource(context.contentResolver, filesystem), repository)
    },
    createExifSource = { filesystem -> AndroidExifSource(context.contentResolver, filesystem) },
)

/**
 * Images and videos indexed by MediaStore across all shared-storage volumes.
 * Needs READ_MEDIA_IMAGES + READ_MEDIA_VIDEO (Android 13+) or READ_EXTERNAL_STORAGE (12 and below).
 */
internal class MediaStoreSource(
    private val resolver: ContentResolver,
    private val filesystem: IoLane,
) : MediaSource {
    override suspend fun items(): List<MediaItem> = filesystem.run("MediaStore items") {
        val projection = arrayOf(
            // DATA is deprecated for file access, but it is still the only column with the absolute path
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
        val mediaTypes = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
        )
        val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val cursor = Diagnostics.logger.operation(LogTags.MEDIA_STORE, "resolver.query $uri") {
            resolver.query(uri, projection, selection, mediaTypes, null)
        }
            ?: throw IOException("MediaStore query returned no cursor; inventory was not reconciled.")
        Diagnostics.logger.operation(LogTags.MEDIA_STORE, "cursor consume/close $uri") { cursor.use {
            val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            buildList {
                while (Diagnostics.logger.blockingOperation(LogTags.MEDIA_STORE, "cursor.moveToNext $uri") { cursor.moveToNext() }) {
                    currentCoroutineContext().ensureActive()
                    val path = cursor.getString(pathColumn) ?: continue
                    add(
                        MediaItem(
                            path = path.toPath(),
                            sizeBytes = cursor.getLong(sizeColumn),
                            modifiedAt = if (cursor.isNull(modifiedColumn)) null
                            else cursor.getLong(modifiedColumn) * MILLIS_PER_SECOND,
                        ),
                    )
                } }
            }
        }
    }
}
