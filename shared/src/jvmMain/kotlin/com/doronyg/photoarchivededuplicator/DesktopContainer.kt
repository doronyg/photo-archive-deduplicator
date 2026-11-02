package com.doronyg.photoarchivededuplicator

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.doronyg.photoarchivededuplicator.db.FilesDatabase
import com.doronyg.photoarchivededuplicator.scan.DirectoryScanner
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.util.Properties

private const val APP_DIRECTORY_NAME = "PhotoArchiveDeduplicator"
private const val DATABASE_FILE_NAME = "inventory.db"
private const val BUSY_TIMEOUT_MILLIS = 5_000

/** Per-user app data folder following each OS's convention. */
fun desktopDataDirectory(): Path {
    val home = System.getProperty("user.home").toPath()
    val os = System.getProperty("os.name").lowercase()
    val base = when {
        "mac" in os -> home / "Library" / "Application Support"
        "win" in os -> System.getenv("APPDATA")?.toPath() ?: home
        else -> System.getenv("XDG_DATA_HOME")?.toPath() ?: (home / ".local" / "share")
    }
    return base / APP_DIRECTORY_NAME
}

/** Builds the container with the inventory in a SQLite file. Call once, at startup. */
suspend fun createDesktopContainer(dataDirectory: Path = desktopDataDirectory()): AppContainer = createAppContainer(
    prepareFilesystem = {
        Diagnostics.logger.blockingOperation(LogTags.FILE_SYSTEM, "createDirectories $dataDirectory") {
            FileSystem.SYSTEM.createDirectories(dataDirectory)
        }
    },
    openDriver = {
        val properties = Properties().apply {
            // WAL and a bounded wait also help with other processes accessing the file.
            put("journal_mode", "WAL")
            put("busy_timeout", BUSY_TIMEOUT_MILLIS.toString())
        }
        Diagnostics.logger.blockingOperation(LogTags.DATABASE, "JdbcSqliteDriver ${dataDirectory / DATABASE_FILE_NAME}") { JdbcSqliteDriver(
            url = "jdbc:sqlite:${dataDirectory / DATABASE_FILE_NAME}",
            properties = properties,
            schema = FilesDatabase.Schema.synchronous(),
        ) }
    },
    createScanner = { repository, filesystem ->
        DirectoryScanner(FileSystem.SYSTEM, repository, filesystemLane = filesystem)
    },
)
